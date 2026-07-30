package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocModuleCharacter;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocModuleCharacterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CocModuleCharacterInstantiationService {

    private final CocModuleCharacterMapper moduleCharacterMapper;
    private final CocCharacterMapper characterMapper;
    private final CocCharacterSkillMapper skillMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public int instantiate(Long moduleId, Long runId) {
        if (moduleId == null || runId == null) {
            throw new UserRequestException("模组和跑团id不能为空");
        }
        try {
            return instantiateTemplates(moduleId, runId);
        } catch (UserRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UserRequestException(
                    "模组人物卡数据无法置入", exception);
        }
    }

    private int instantiateTemplates(Long moduleId, Long runId) {
        List<CocModuleCharacter> templates =
                moduleCharacterMapper.selectList(
                        new LambdaQueryWrapper<CocModuleCharacter>()
                                .eq(CocModuleCharacter::getModuleId,
                                        moduleId)
                                .orderByAsc(
                                        CocModuleCharacter::getSortOrder)
                                .orderByAsc(CocModuleCharacter::getId));
        Set<String> names = existingNames(runId);
        int copied = 0;
        for (CocModuleCharacter template : templates) {
            CharacterCardVO card = template.getCardData() == null
                    ? null
                    : objectMapper.convertValue(
                            template.getCardData(),
                            CharacterCardVO.class);
            CocCharacter source =
                    card == null ? null : card.getCharacter();
            if (source == null || !StringUtils.hasText(source.getName())) {
                throw new UserRequestException("模组人物卡数据无法置入");
            }
            String name = source.getName().trim();
            if (!names.add(name)) {
                continue;
            }
            copyCard(card, name, runId);
            copied++;
        }
        return copied;
    }

    private Set<String> existingNames(Long runId) {
        Set<String> names = new HashSet<>();
        for (CocCharacter character : characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .orderByAsc(CocCharacter::getId))) {
            if (StringUtils.hasText(character.getName())) {
                names.add(character.getName().trim());
            }
        }
        return names;
    }

    private void copyCard(
            CharacterCardVO card, String name, Long runId) {
        LocalDateTime now = LocalDateTime.now();
        CocCharacter character = new CocCharacter();
        BeanUtils.copyProperties(card.getCharacter(), character);
        character.setId(null)
                .setRunId(runId)
                .setActorType("NPC")
                .setParticipantId(null)
                .setCreationMethod("MODULE")
                .setName(name)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        characterMapper.insert(character);
        Long characterId = character.getId();
        for (CocCharacterSkill source : safe(card.getSkills())) {
            CocCharacterSkill skill = new CocCharacterSkill();
            BeanUtils.copyProperties(source, skill);
            skill.setId(null).setCharacterId(characterId);
            skillMapper.insert(skill);
        }
        for (CocCharacterWeapon source : safe(card.getWeapons())) {
            CocCharacterWeapon weapon = new CocCharacterWeapon();
            BeanUtils.copyProperties(source, weapon);
            weapon.setId(null).setCharacterId(characterId);
            weaponMapper.insert(weapon);
        }
        if (card.getProfile() != null) {
            CocCharacterProfile profile = new CocCharacterProfile();
            BeanUtils.copyProperties(card.getProfile(), profile);
            profile.setId(null).setCharacterId(characterId);
            profileMapper.insert(profile);
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
