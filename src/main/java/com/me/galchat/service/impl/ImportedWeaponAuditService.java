package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.dto.ImportedWeaponAuditModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImportedWeaponAuditService {

    private static final int MAX_RISK_TAGS = 6;
    private static final int MAX_RISK_TAG_LENGTH = 24;

    private final CocCharacterMapper characterMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final ImportedWeaponAuditModel auditModel;

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long characterId) {
        CocCharacter character = characterId == null
                ? null : characterMapper.selectById(characterId);
        if (character == null
                || !"IMPORT".equals(character.getCreationMethod())) {
            return;
        }
        List<CocCharacterWeapon> weapons = weaponMapper.selectList(
                new LambdaQueryWrapper<CocCharacterWeapon>()
                        .eq(CocCharacterWeapon::getCharacterId, characterId)
                        .orderByAsc(CocCharacterWeapon::getId));
        if (weapons == null || weapons.isEmpty()) {
            return;
        }
        ImportedWeaponAuditModels.Response response = auditModel.review(
                character, List.copyOf(weapons));
        if (response == null || response.weapons() == null) {
            return;
        }
        Map<Long, CocCharacterWeapon> ownedWeapons = new LinkedHashMap<>();
        for (CocCharacterWeapon weapon : weapons) {
            if (weapon != null && weapon.getId() != null) {
                ownedWeapons.putIfAbsent(weapon.getId(), weapon);
            }
        }
        Set<Long> applied = new LinkedHashSet<>();
        for (ImportedWeaponAuditModels.Review review : response.weapons()) {
            if (review == null || review.weaponId() == null
                    || review.abnormal() == null
                    || !applied.add(review.weaponId())) {
                continue;
            }
            CocCharacterWeapon weapon = ownedWeapons.get(review.weaponId());
            if (weapon == null) {
                continue;
            }
            boolean abnormal = Boolean.TRUE.equals(review.abnormal());
            List<String> riskTags = abnormal
                    ? normalizeRiskTags(review.riskTags())
                    : List.of();
            weapon.setAbnormal(abnormal).setRiskTags(riskTags);
            weaponMapper.updateById(new CocCharacterWeapon()
                    .setId(weapon.getId())
                    .setAbnormal(abnormal)
                    .setRiskTags(riskTags));
        }
    }

    private List<String> normalizeRiskTags(List<String> riskTags) {
        if (riskTags == null || riskTags.isEmpty()) {
            return List.of("异常携带");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : riskTags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String value = tag.trim();
            if (value.length() > MAX_RISK_TAG_LENGTH) {
                value = value.substring(0, MAX_RISK_TAG_LENGTH);
            }
            normalized.add(value);
            if (normalized.size() == MAX_RISK_TAGS) {
                break;
            }
        }
        return normalized.isEmpty()
                ? List.of("异常携带")
                : new ArrayList<>(normalized);
    }
}
