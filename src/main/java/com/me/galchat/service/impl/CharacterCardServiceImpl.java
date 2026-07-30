package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.CharacterCardCreateDTO;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CharacterCardServiceImpl implements ICharacterCardService {

    private final CocCharacterMapper characterMapper;
    private final CocCharacterSkillMapper skillMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final CocSkillDefMapper skillDefMapper;
    private final CharacterTemplateMapper characterTemplateMapper;
    private final UserInfoMapper userInfoMapper;
    private final GroupConversationMapper conversationMapper;

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
        CocCharacter character = parsed.character()
                .setRunId(createDTO.getRunId())
                .setParticipantId(createDTO.getParticipantId())
                .setActorType(resolveActorType(createDTO.getParticipantId()))
                .setCreationMethod("IMPORT");
        fillPlayerAndImage(character, createDTO.getParticipantId());
        characterMapper.insert(character);
        Long characterId = character.getId();

        for (CocCharacterSkill skill : parsed.skills()) {
            skill.setCharacterId(characterId);
            skillMapper.insert(skill);
        }
        for (CocCharacterWeapon weapon : parsed.weapons()) {
            weapon.setCharacterId(characterId);
            weaponMapper.insert(weapon);
        }
        parsed.profile().setCharacterId(characterId);
        profileMapper.insert(parsed.profile());
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
    public CharacterCardVO getByRunIdAndParticipantId(Long runId, Long participantId) {
        if (runId == null) {
            throw new UserRequestException("runId不能为空");
        }
        LambdaQueryWrapper<CocCharacter> query = new LambdaQueryWrapper<CocCharacter>()
                .eq(CocCharacter::getRunId, runId);
        if (participantId == null) {
            query.isNull(CocCharacter::getParticipantId)
                    .eq(CocCharacter::getActorType, "PLAYER");
        } else {
            query.eq(CocCharacter::getParticipantId, participantId)
                    .eq(CocCharacter::getActorType, "BOT");
        }
        CocCharacter character = characterMapper.selectOne(query);
        if (character == null) {
            throw new UserRequestException("人物卡不存在");
        }
        return build(character);
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
        return buildDiceCharacter(requireCharacterByName(runId, characterName));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuickNotes(
            Long runId, String characterName, String quickNotes) {
        CocCharacter character =
                requireCharacterByName(runId, characterName);
        String normalizedNotes = quickNotes == null
                || quickNotes.isBlank() ? null : quickNotes.trim();
        character.setQuickNotes(normalizedNotes)
                .setUpdatedAt(java.time.LocalDateTime.now());
        if (characterMapper.updateById(character) == 0) {
            throw new UserRequestException("人物卡不存在");
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
        for (CocCharacter character : characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .orderByAsc(CocCharacter::getId))) {
            result.add(buildDiceCharacter(character));
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
        List<CocCharacterSkill> skills = skillMapper.selectList(new LambdaQueryWrapper<CocCharacterSkill>()
                .eq(CocCharacterSkill::getCharacterId, id)
                .orderByAsc(CocCharacterSkill::getId));
        List<CocCharacterWeapon> weapons = weaponMapper.selectList(new LambdaQueryWrapper<CocCharacterWeapon>()
                .eq(CocCharacterWeapon::getCharacterId, id)
                .orderByAsc(CocCharacterWeapon::getId));
        CocCharacterProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<CocCharacterProfile>()
                .eq(CocCharacterProfile::getCharacterId, id));
        return new CharacterCardVO(character, skills, weapons, profile);
    }

    private CocDiceCharacterVO buildDiceCharacter(CocCharacter character) {
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
        for (CocCharacterSkill skill : skillMapper.selectList(
                new LambdaQueryWrapper<CocCharacterSkill>()
                        .eq(CocCharacterSkill::getCharacterId, character.getId())
                        .orderByAsc(CocCharacterSkill::getId))) {
            if (skill.getDisplayName() != null && !skill.getDisplayName().isBlank()
                    && skill.getValue() != null) {
                checkValues.put(skill.getDisplayName().trim(), skill.getValue());
            }
        }
        return new CocDiceCharacterVO(
                character.getId(),
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
        Map<String, CocSkillDef> definitions = skillDefMapper.selectList(null).stream()
                .collect(Collectors.toMap(CocSkillDef::getName, Function.identity()));
        int spent = 0;
        for (CocCharacterSkill skill : skills) {
            if ("克苏鲁神话".equals(skill.getDisplayName()) && skill.getValue() != 0) {
                throw new UserRequestException("新建角色卡的克苏鲁神话点数必须为0");
            }
            CocSkillDef definition = findDefinition(skill.getDisplayName(), definitions);
            int baseValue = resolveBaseValue(definition, character);
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

    private CocSkillDef findDefinition(String skillName, Map<String, CocSkillDef> definitions) {
        CocSkillDef exact = definitions.get(skillName);
        if (exact != null) {
            return exact;
        }
        int separator = skillName.indexOf(':');
        if (separator < 0) {
            return null;
        }
        CocSkillDef specialization = definitions.get(skillName.substring(separator + 1));
        return specialization != null ? specialization : definitions.get(skillName.substring(0, separator));
    }

    private int resolveBaseValue(CocSkillDef definition, CocCharacter character) {
        if (definition == null) {
            return 0;
        }
        if (definition.getBaseValue() != null) {
            return definition.getBaseValue();
        }
        return switch (definition.getBaseFormula() == null ? "" : definition.getBaseFormula()) {
            case "DEX/2" -> character.getDex() / 2;
            case "EDU" -> character.getEdu();
            default -> 0;
        };
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
