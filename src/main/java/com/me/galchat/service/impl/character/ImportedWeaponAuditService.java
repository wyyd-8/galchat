package com.me.galchat.service.impl.character;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.domain.dto.ImportedWeaponAuditModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ImportedWeaponAuditService {

    private static final String UNKNOWN_WEAPON_FALLBACK_CODE = "LARGE_CLUB";
    private static final List<String> UNKNOWN_WEAPON_RISK_TAGS =
            List.of("未识别武器");

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
        List<CocWeaponCatalogConstant.WeaponDefinition> catalog =
                CocWeaponCatalogConstant.weapons().values().stream()
                        .sorted(Comparator.comparing(
                                CocWeaponCatalogConstant.WeaponDefinition::code))
                        .toList();
        Map<String, String> genericDefaults =
                CocWeaponCatalogConstant.genericTypeDefaults(
                        character.getEra());
        ImportedWeaponAuditModels.Response response = auditModel.review(
                character, List.copyOf(weapons), catalog, genericDefaults);

        Map<Long, CocCharacterWeapon> ownedWeapons = new LinkedHashMap<>();
        for (CocCharacterWeapon weapon : weapons) {
            if (weapon != null && weapon.getId() != null) {
                ownedWeapons.putIfAbsent(weapon.getId(), weapon);
            }
        }
        Map<Long, String> modelMatches = modelMatches(response, ownedWeapons);
        for (CocCharacterWeapon weapon : ownedWeapons.values()) {
            String broadDefault = genericDefaults.get(
                    normalizeName(weapon.getName()));
            String catalogCode = broadDefault != null
                    ? broadDefault : modelMatches.get(weapon.getId());
            CocWeaponCatalogConstant.WeaponDefinition definition =
                    catalogCode == null ? null
                            : CocWeaponCatalogConstant.weapons().get(catalogCode);
            boolean unrecognized = definition == null;
            if (unrecognized) {
                definition = CocWeaponCatalogConstant.require(
                        UNKNOWN_WEAPON_FALLBACK_CODE);
            }
            hydrate(weapon, definition, unrecognized);
        }
    }

    private Map<Long, String> modelMatches(
            ImportedWeaponAuditModels.Response response,
            Map<Long, CocCharacterWeapon> ownedWeapons) {
        Map<Long, String> matches = new LinkedHashMap<>();
        if (response == null || response.weapons() == null) {
            return matches;
        }
        for (ImportedWeaponAuditModels.Review review : response.weapons()) {
            if (review == null || review.weaponId() == null
                    || !ownedWeapons.containsKey(review.weaponId())) {
                continue;
            }
            String code = review.catalogCode() == null
                    ? null : review.catalogCode().trim();
            if (code != null
                    && CocWeaponCatalogConstant.weapons().containsKey(code)) {
                matches.putIfAbsent(review.weaponId(), code);
            }
        }
        return matches;
    }

    private void hydrate(
            CocCharacterWeapon weapon,
            CocWeaponCatalogConstant.WeaponDefinition definition,
            boolean unrecognized) {
        applyDefinition(weapon, definition);
        markUnrecognized(weapon, unrecognized);
        CocCharacterWeapon update = new CocCharacterWeapon()
                .setId(weapon.getId());
        applyDefinition(update, definition);
        markUnrecognized(update, unrecognized);
        weaponMapper.updateById(update);
    }

    private void markUnrecognized(
            CocCharacterWeapon weapon,
            boolean unrecognized) {
        if (unrecognized) {
            weapon.setAbnormal(UNKNOWN_WEAPON_RISK_TAGS.size() >= 2)
                    .setRiskTags(UNKNOWN_WEAPON_RISK_TAGS);
        }
    }

    private void applyDefinition(
            CocCharacterWeapon weapon,
            CocWeaponCatalogConstant.WeaponDefinition definition) {
        weapon.setSkillName(definition.requiredSkillName())
                .setDamage(definition.damage())
                .setRange(definition.range())
                .setAttacksPerRound(definition.attacksPerRound())
                .setAmmoCapacity(definition.ammoCapacity())
                .setRemainingAmmo(definition.ammoCapacity())
                .setMalfunction(definition.malfunction())
                .setCanImpale(definition.canImpale())
                .setIsBroken(false)
                .setAbnormal(definition.abnormal())
                .setRiskTags(List.copyOf(definition.riskTags()))
                .setNotes(definition.notes());
    }

    private String normalizeName(String name) {
        return name == null ? null : name.trim();
    }
}
