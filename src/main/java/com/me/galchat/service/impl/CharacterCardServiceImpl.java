package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.CharacterCardCreateDTO;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.domain.dto.KpWeaponStateDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.utils.CurrentHolder;
import com.me.galchat.utils.DiceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class CharacterCardServiceImpl implements ICharacterCardService {

    private final CocCharacterMapper characterMapper;
    private final CocCharacterSkillMapper skillMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final CocSkillDefMapper skillDefMapper;
    private final CharacterSkillResolver skillResolver;
    private final CharacterTemplateMapper characterTemplateMapper;
    private final UserInfoMapper userInfoMapper;
    private final GroupConversationMapper conversationMapper;
    private final ImportedWeaponAuditQueue weaponAuditQueue;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CharacterCardVO create(CharacterCardCreateDTO createDTO) {
        if (createDTO == null || createDTO.getRunId() == null) {
            throw new UserRequestException("runId不能为空");
        }
        requireTrpgRun(createDTO.getRunId());
        CharacterCardImportParser.ParsedCharacterCard parsed =
                CharacterCardImportParser.parse(createDTO.getCharacterText());
        requireUniqueName(
                createDTO.getRunId(), parsed.character());
        validateAndFillSkills(parsed.character(), parsed.skills());
        List<CocCharacterSkill> skillOverrides =
                skillResolver.normalizeOverrides(
                        parsed.character(), parsed.skills(), List.of());
        CocCharacter character = parsed.character()
                .setRunId(createDTO.getRunId())
                .setParticipantId(createDTO.getParticipantId())
                .setActorType(resolveActorType(createDTO.getParticipantId()))
                .setCreationMethod("IMPORT");
        fillPlayerAndImage(character, createDTO.getParticipantId());
        characterMapper.insert(character);
        Long characterId = character.getId();

        for (CocCharacterSkill skill : skillOverrides) {
            skill.setCharacterId(characterId);
            skillMapper.insert(skill);
        }
        for (CocCharacterWeapon weapon : parsed.weapons()) {
            weapon.setCharacterId(characterId);
            weaponMapper.insert(weapon);
        }
        parsed.profile().setCharacterId(characterId);
        profileMapper.insert(parsed.profile());
        if (!parsed.weapons().isEmpty()) {
            weaponAuditQueue.submitAfterCommit(characterId);
        }
        return requireById(characterId);
    }

    private void requireUniqueName(
            Long runId, CocCharacter character) {
        String name = character == null || character.getName() == null
                ? null : character.getName().trim();
        if (name == null || name.isEmpty()) {
            throw new UserRequestException("人物卡名称不能为空");
        }
        character.setName(name);
        List<CocCharacter> matches = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .eq(CocCharacter::getName, name));
        if (matches != null && !matches.isEmpty()) {
            throw new UserRequestException(
                    "同一跑团内人物卡名称不能重复：" + name);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireId(id);
        if (characterMapper.selectById(id) == null) {
            throw new UserRequestException("人物卡不存在");
        }
        skillMapper.delete(new LambdaQueryWrapper<CocCharacterSkill>()
                .eq(CocCharacterSkill::getCharacterId, id));
        weaponMapper.delete(new LambdaQueryWrapper<CocCharacterWeapon>()
                .eq(CocCharacterWeapon::getCharacterId, id));
        profileMapper.delete(new LambdaQueryWrapper<CocCharacterProfile>()
                .eq(CocCharacterProfile::getCharacterId, id));
        characterMapper.deleteById(id);
    }

    @Override
    public CharacterCardVO getById(Long id) {
        requireId(id);
        return requireById(id);
    }

    @Override
    public List<CocDiceCharacterVO> listInvestigatorCards(Long runId) {
        return listDiceCharacters(runId).stream()
                .filter(card -> "PLAYER".equals(card.actorType())
                        || "BOT".equals(card.actorType()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollResultVO rollLuck(Long id) {
        requireId(id);
        CocCharacter character = characterMapper.selectById(id);
        if (character == null) {
            throw new UserRequestException("人物卡不存在");
        }
        if (character.getLuckCurrent() != null) {
            throw new UserRequestException("幸运值已绑定");
        }
        DiceRollResultVO result = DiceUtils.roll("3D6 * 5");
        int updated = characterMapper.update(null, new UpdateWrapper<CocCharacter>()
                .eq("id", id)
                .isNull("luck_current")
                .set("luck_current", result.getResult()));
        if (updated == 0) {
            throw new UserRequestException("幸运值已绑定");
        }
        return result;
    }

    @Override
    public CocDiceCharacterVO requireDiceCharacter(Long runId, String characterName) {
        return buildDiceCharacter(
                requireCharacterByName(runId, characterName),
                skillDefMapper.selectList(null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuickNotes(
            Long runId, String characterName, String quickNotes) {
        CocCharacter character =
                requireCharacterByName(runId, characterName);
        String normalizedNotes = quickNotes == null
                || quickNotes.isBlank() ? null : quickNotes.trim();
        int updated = characterMapper.update(null,
                new LambdaUpdateWrapper<CocCharacter>()
                        .eq(CocCharacter::getId, character.getId())
                        .set(CocCharacter::getQuickNotes, normalizedNotes)
                        .set(CocCharacter::getUpdatedAt,
                                java.time.LocalDateTime.now()));
        if (updated == 0) {
            throw new UserRequestException("人物卡不存在");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpWeaponStateDTOs.Result updateWeaponState(
            Long runId, String characterName, String weaponName,
            KpWeaponStateDTOs.Update update) {
        if (update == null
                || update.remainingAmmo() == null
                && update.broken() == null) {
            throw new UserRequestException("至少需要提供一个武器状态更新值");
        }
        if (weaponName == null || weaponName.isBlank()) {
            throw new UserRequestException("武器名称不能为空");
        }
        CocCharacter character = requireCharacterByName(
                runId, characterName);
        List<CocCharacterWeapon> matches =
                weaponMapper.selectByCharacterIdAndNameForUpdate(
                        character.getId(), weaponName.trim());
        if (matches == null || matches.isEmpty()) {
            throw new UserRequestException("人物卡没有该武器");
        }
        if (matches.size() > 1) {
            throw new UserRequestException("人物卡武器名称不唯一");
        }
        CocCharacterWeapon weapon = matches.getFirst();
        if (update.remainingAmmo() != null) {
            Integer capacity = weapon.getAmmoCapacity();
            if (capacity == null) {
                throw new UserRequestException("该武器不记录弹药");
            }
            if (update.remainingAmmo() < 0
                    || update.remainingAmmo() > capacity) {
                throw new UserRequestException(
                        "剩余弹药必须在0到弹药容量之间");
            }
        }
        boolean brokenBefore = Boolean.TRUE.equals(
                weapon.getIsBroken());
        if (brokenBefore && Boolean.FALSE.equals(update.broken())) {
            throw new UserRequestException(
                    "武器修复不能通过状态更新工具完成");
        }
        Integer ammoAfter = update.remainingAmmo() == null
                ? weapon.getRemainingAmmo() : update.remainingAmmo();
        boolean brokenAfter = update.broken() == null
                ? brokenBefore : update.broken();
        boolean changed = !Objects.equals(
                weapon.getRemainingAmmo(), ammoAfter)
                || brokenBefore != brokenAfter;
        if (changed) {
            weapon.setRemainingAmmo(ammoAfter)
                    .setIsBroken(brokenAfter);
            if (weaponMapper.updateById(weapon) == 0) {
                throw new UserRequestException("武器不存在");
            }
        }
        return new KpWeaponStateDTOs.Result(
                character.getName(), weapon.getName(),
                ammoAfter, weapon.getAmmoCapacity(),
                brokenAfter, changed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpCharacterAttributeDTOs.Result adjustBasicAttributes(
            Long runId, String characterName,
            KpCharacterAttributeDTOs.Adjustments adjustments) {
        if (adjustments == null || allAdjustmentsMissing(adjustments)) {
            throw new UserRequestException(
                    "至少需要提供一个基础属性修正值");
        }
        CocCharacter character =
                requireCharacterByName(runId, characterName);
        Map<String, KpCharacterAttributeDTOs.ValueChange> changes =
                new LinkedHashMap<>();
        if (adjustments.str() != null) {
            character.setStr(adjust(
                    changes, "STR", character.getStr(),
                    adjustments.str()));
        }
        if (adjustments.con() != null) {
            character.setCon(adjust(
                    changes, "CON", character.getCon(),
                    adjustments.con()));
        }
        if (adjustments.siz() != null) {
            character.setSiz(adjust(
                    changes, "SIZ", character.getSiz(),
                    adjustments.siz()));
        }
        if (adjustments.dex() != null) {
            character.setDex(adjust(
                    changes, "DEX", character.getDex(),
                    adjustments.dex()));
        }
        if (adjustments.app() != null) {
            character.setApp(adjust(
                    changes, "APP", character.getApp(),
                    adjustments.app()));
        }
        if (adjustments.intValue() != null) {
            character.setIntValue(adjust(
                    changes, "INT", character.getIntValue(),
                    adjustments.intValue()));
        }
        if (adjustments.pow() != null) {
            character.setPow(adjust(
                    changes, "POW", character.getPow(),
                    adjustments.pow()));
        }
        if (adjustments.edu() != null) {
            character.setEdu(adjust(
                    changes, "EDU", character.getEdu(),
                    adjustments.edu()));
        }
        CharacterCardRules.DamageBuild damageBuild =
                CharacterCardRules.deriveDamageBuild(
                        character.getStr(), character.getSiz());
        character.setDamageBonus(damageBuild.damageBonus())
                .setBuild(damageBuild.build())
                .setUpdatedAt(java.time.LocalDateTime.now());
        if (characterMapper.updateById(character) == 0) {
            throw new UserRequestException("人物卡不存在");
        }
        return new KpCharacterAttributeDTOs.Result(
                character.getName(),
                Collections.unmodifiableMap(changes),
                damageBuild.damageBonus(), damageBuild.build());
    }

    private boolean allAdjustmentsMissing(
            KpCharacterAttributeDTOs.Adjustments adjustments) {
        return adjustments.str() == null
                && adjustments.con() == null
                && adjustments.siz() == null
                && adjustments.dex() == null
                && adjustments.app() == null
                && adjustments.intValue() == null
                && adjustments.pow() == null
                && adjustments.edu() == null;
    }

    private int adjust(
            Map<String, KpCharacterAttributeDTOs.ValueChange> changes,
            String attribute, Integer before, int correction) {
        if (before == null) {
            throw new UserRequestException(
                    "人物卡" + attribute + "属性不存在");
        }
        int after = (int) Math.clamp(
                (long) before + correction, 0L, 100L);
        changes.put(attribute,
                new KpCharacterAttributeDTOs.ValueChange(before, after));
        return after;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackBasicAttributeAdjustment(
            Long runId,
            KpCharacterAttributeDTOs.Result executedResult) {
        if (executedResult == null
                || executedResult.changes() == null
                || executedResult.changes().isEmpty()) {
            throw new IllegalStateException("基础属性调整结果不完整，无法回滚");
        }
        CocCharacter character = requireCharacterByName(
                runId, executedResult.characterName());
        for (Map.Entry<String, KpCharacterAttributeDTOs.ValueChange> entry
                : executedResult.changes().entrySet()) {
            KpCharacterAttributeDTOs.ValueChange change = entry.getValue();
            Integer current = basicAttribute(character, entry.getKey());
            if (change == null
                    || !Objects.equals(current, change.after())) {
                throw new IllegalStateException(
                        "人物卡" + entry.getKey()
                                + "已发生后续变化，无法安全回滚");
            }
        }
        executedResult.changes().forEach((attribute, change) ->
                setBasicAttribute(character, attribute, change.before()));
        CharacterCardRules.DamageBuild damageBuild =
                CharacterCardRules.deriveDamageBuild(
                        character.getStr(), character.getSiz());
        character.setDamageBonus(damageBuild.damageBonus())
                .setBuild(damageBuild.build())
                .setUpdatedAt(java.time.LocalDateTime.now());
        if (characterMapper.updateById(character) == 0) {
            throw new IllegalStateException("人物卡基础属性回滚失败");
        }
    }

    private Integer basicAttribute(
            CocCharacter character, String attribute) {
        return switch (attribute) {
            case "STR" -> character.getStr();
            case "CON" -> character.getCon();
            case "SIZ" -> character.getSiz();
            case "DEX" -> character.getDex();
            case "APP" -> character.getApp();
            case "INT" -> character.getIntValue();
            case "POW" -> character.getPow();
            case "EDU" -> character.getEdu();
            default -> throw new IllegalStateException(
                    "未知基础属性" + attribute + "，无法回滚");
        };
    }

    private void setBasicAttribute(
            CocCharacter character, String attribute, int value) {
        switch (attribute) {
            case "STR" -> character.setStr(value);
            case "CON" -> character.setCon(value);
            case "SIZ" -> character.setSiz(value);
            case "DEX" -> character.setDex(value);
            case "APP" -> character.setApp(value);
            case "INT" -> character.setIntValue(value);
            case "POW" -> character.setPow(value);
            case "EDU" -> character.setEdu(value);
            default -> throw new IllegalStateException(
                    "未知基础属性" + attribute + "，无法回滚");
        }
    }

    private CocCharacter requireCharacterByName(
            Long runId, String characterName) {
        requireRunId(runId);
        if (characterName == null || characterName.isBlank()) {
            throw new UserRequestException("人物卡名称不能为空");
        }
        String normalizedName = characterName.trim();
        List<CocCharacter> matches = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .eq(CocCharacter::getName, normalizedName))
                .stream()
                .filter(character -> normalizedName.equals(character.getName()))
                .toList();
        if (matches.isEmpty()) {
            throw new UserRequestException("人物卡不存在");
        }
        if (matches.size() > 1) {
            throw new UserRequestException("人物卡名称不唯一");
        }
        return matches.getFirst();
    }

    @Override
    public List<CocDiceCharacterVO> listDiceCharacters(Long runId) {
        requireRunId(runId);
        List<CocDiceCharacterVO> result = new ArrayList<>();
        List<CocSkillDef> definitions = skillDefMapper.selectList(null);
        for (CocCharacter character : characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .orderByAsc(CocCharacter::getId))) {
            result.add(buildDiceCharacter(character, definitions));
        }
        return List.copyOf(result);
    }

    @Override
    public CocCharacter lockDiceCharacter(Long runId, Long cardId) {
        requireRunId(runId);
        requireId(cardId);
        CocCharacter character = characterMapper.selectByIdAndRunIdForUpdate(runId, cardId);
        if (character == null) {
            throw new UserRequestException("人物卡不存在");
        }
        return character;
    }

    @Override
    public void updateDiceCharacter(CocCharacter character) {
        if (character == null) {
            throw new UserRequestException("人物卡不能为空");
        }
        requireId(character.getId());
        if (characterMapper.updateById(character) == 0) {
            throw new UserRequestException("人物卡不存在");
        }
    }

    private CharacterCardVO requireById(Long id) {
        CocCharacter character = characterMapper.selectById(id);
        if (character == null) {
            throw new UserRequestException("人物卡不存在");
        }
        return build(character);
    }

    private CharacterCardVO build(CocCharacter character) {
        Long id = character.getId();
        List<CocCharacterSkill> skills = skillResolver.resolveEffectiveSkills(
                character,
                skillMapper.selectList(new LambdaQueryWrapper<CocCharacterSkill>()
                        .eq(CocCharacterSkill::getCharacterId, id)
                        .orderByAsc(CocCharacterSkill::getId)),
                skillDefMapper.selectList(null));
        List<CocCharacterWeapon> weapons = weaponMapper.selectList(new LambdaQueryWrapper<CocCharacterWeapon>()
                .eq(CocCharacterWeapon::getCharacterId, id)
                .orderByAsc(CocCharacterWeapon::getId));
        CocCharacterProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<CocCharacterProfile>()
                .eq(CocCharacterProfile::getCharacterId, id));
        return new CharacterCardVO(character, skills, weapons, profile);
    }

    private CocDiceCharacterVO buildDiceCharacter(
            CocCharacter character, List<CocSkillDef> definitions) {
        Map<String, Integer> checkValues = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        putAliases(checkValues, character.getStr(), "STR", "力量");
        putAliases(checkValues, character.getCon(), "CON", "体质");
        putAliases(checkValues, character.getSiz(), "SIZ", "体型");
        putAliases(checkValues, character.getDex(), "DEX", "敏捷");
        putAliases(checkValues, character.getApp(), "APP", "外貌");
        putAliases(checkValues, character.getIntValue(), "INT", "智力");
        putAliases(checkValues, character.getPow(), "POW", "意志");
        putAliases(checkValues, character.getEdu(), "EDU", "教育");
        putAliases(checkValues, character.getSanCurrent(), "SAN", "理智");
        putAliases(checkValues, character.getLuckCurrent(), "LUCK", "幸运");
        List<CocCharacterSkill> overrides = skillMapper.selectList(
                new LambdaQueryWrapper<CocCharacterSkill>()
                        .eq(CocCharacterSkill::getCharacterId, character.getId())
                        .orderByAsc(CocCharacterSkill::getId));
        for (CocCharacterSkill skill : skillResolver.resolveEffectiveSkills(
                character, overrides, definitions)) {
            if (skill.getDisplayName() != null && !skill.getDisplayName().isBlank()
                    && skill.getValue() != null) {
                checkValues.put(skill.getDisplayName().trim(), skill.getValue());
            }
        }
        return new CocDiceCharacterVO(
                character.getId(),
                character.getActorType(),
                character.getParticipantId(),
                character.getName(),
                Collections.unmodifiableMap(checkValues),
                character.getHpCurrent(),
                character.getHpMax(),
                character.getSanCurrent(),
                character.getSanMax(),
                character.getCon(),
                character.getArmor(),
                character.getMajorWound(),
                character.getUnconscious(),
                character.getDying(),
                character.getDead(),
                character.getTemporaryInsanity(),
                character.getTemporaryInsanityPhase(),
                character.getTemporaryInsanityRemainingHours());
    }

    private void putAliases(
            Map<String, Integer> checkValues, Integer value, String english, String chinese) {
        if (value != null) {
            checkValues.put(english, value);
            checkValues.put(chinese, value);
        }
    }

    private void requireId(Long id) {
        if (id == null) {
            throw new UserRequestException("人物卡id不能为空");
        }
    }

    private void requireRunId(Long runId) {
        if (runId == null) {
            throw new UserRequestException("runId不能为空");
        }
    }

    private void requireTrpgRun(Long runId) {
        GroupConversation conversation =
                conversationMapper.selectById(runId);
        if (conversation == null
                || !GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException(
                    "runId必须是TRPG群聊id");
        }
    }

    void validateAndFillSkills(CocCharacter character, List<CocCharacterSkill> skills) {
        Map<String, CocSkillDef> definitions = skillResolver.definitionsByName(
                skillDefMapper.selectList(null));
        int spent = 0;
        for (CocCharacterSkill skill : skills) {
            if ("克苏鲁神话".equals(skill.getDisplayName()) && skill.getValue() != 0) {
                throw new UserRequestException("新建角色卡的克苏鲁神话点数必须为0");
            }
            CocSkillDef definition = skillResolver.findDefinition(
                    skill.getDisplayName(), definitions);
            int baseValue = skillResolver.resolveBaseValue(definition, character);
            skill.setBaseValue(baseValue);
            skill.setSkillDefId(definition == null ? null : definition.getId());
            skill.setCategory(definition == null ? skill.getCategory() : definition.getCategory());
            skill.setIsCustom(definition == null);
            spent += skill.getValue() - baseValue;
        }
        int maxAttribute = List.of(character.getStr(), character.getCon(), character.getSiz(), character.getDex(),
                character.getApp(), character.getIntValue(), character.getPow(), character.getEdu())
                .stream().mapToInt(Integer::intValue).max().orElseThrow();
        int budget = character.getEdu() * 2 + character.getIntValue() * 2 + maxAttribute * 2;
        if (spent > budget) {
            throw new UserRequestException("角色卡技能点超过上限，当前消耗" + spent + "，上限" + budget);
        }
    }

    static String resolveActorType(Long participantId) {
        return participantId == null ? "PLAYER" : "BOT";
    }

    void fillPlayerAndImage(CocCharacter character, Long participantId) {
        if (participantId != null) {
            CharacterTemplate template = characterTemplateMapper.selectById(participantId);
            if (template == null) {
                throw new UserRequestException("角色模板不存在");
            }
            character.setPlayerName(template.getName()).setImage(template.getImage());
            return;
        }
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserAuthException("用户未登录");
        }
        UserInfo user = userInfoMapper.selectById(userId.longValue());
        if (user == null) {
            throw new UserRequestException("玩家不存在");
        }
        character.setPlayerName(user.getUsername()).setImage(null);
    }
}
