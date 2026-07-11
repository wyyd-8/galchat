package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.me.galchat.domain.dto.CharacterCardCreateDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.utils.DiceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CharacterCardVO create(CharacterCardCreateDTO createDTO) {
        if (createDTO == null || createDTO.getRunId() == null) {
            throw new UserRequestException("runId不能为空");
        }
        CharacterCardImportParser.ParsedCharacterCard parsed =
                CharacterCardImportParser.parse(createDTO.getCharacterText());
        validateAndFillSkills(parsed.character(), parsed.skills());
        CocCharacter character = parsed.character()
                .setRunId(createDTO.getRunId())
                .setParticipantId(createDTO.getParticipantId())
                .setActorType(resolveActorType(createDTO.getParticipantId()))
                .setCreationMethod("IMPORT");
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

    private void requireId(Long id) {
        if (id == null) {
            throw new UserRequestException("人物卡id不能为空");
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
}
