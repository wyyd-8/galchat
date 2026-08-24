package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.domain.dto.KpQuickNpcDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgQuickNpcTemplateService {

    private static final Set<String> STRENGTHS =
            Set.of("WEAK", "MEDIUM", "STRONG");
    private static final Map<String, String> WEAPON_CODES = Map.of(
            "LARGE_CLUB", "LARGE_CLUB",
            "MEDIUM_KNIFE", "MEDIUM_KNIFE",
            "PISTOL", "PISTOL_38_9MM",
            "SMALL_RIFLE", "RIFLE_22_BOLT",
            "HUNTING_RIFLE", "RIFLE_30_LEVER");
    private static final Set<String> WEAPONS = Set.of(
            "UNARMED",
            "LARGE_CLUB",
            "MEDIUM_KNIFE",
            "PISTOL",
            "SMALL_RIFLE",
            "HUNTING_RIFLE");

    private final CocCharacterMapper characterMapper;
    private final CocCharacterSkillMapper skillMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final CocSkillDefMapper skillDefMapper;

    public List<KpQuickNpcDTOs.Spec> validateForRequest(
            Long runId,
            List<KpQuickNpcDTOs.Spec> specs,
            Set<String> reservedNames) {
        if (runId == null) {
            throw new UserRequestException("临时 NPC 缺少游戏团标识");
        }
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        Set<String> reserved = reservedNames == null
                ? Set.of()
                : reservedNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .collect(Collectors.toSet());
        List<CocCharacter> existingCards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId));
        Set<String> existingNames = existingCards == null
                ? Set.of()
                : existingCards.stream()
                .map(CocCharacter::getName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toSet());
        Set<String> quickNames = new HashSet<>();
        List<KpQuickNpcDTOs.Spec> normalized = new ArrayList<>();
        for (KpQuickNpcDTOs.Spec spec : specs) {
            if (spec == null) {
                throw new UserRequestException("临时 NPC 规格不能为空");
            }
            String name = normalizeName(spec.name());
            if (!quickNames.add(name) || reserved.contains(name)) {
                throw new UserRequestException(
                        "参战人物不能重复：" + name);
            }
            if (existingNames.contains(name)) {
                throw new UserRequestException(
                        "人物卡名称已存在：" + name);
            }
            String strength = normalizeChoice(
                    spec.strength(), "临时 NPC 强度", STRENGTHS);
            String weapon = normalizeChoice(
                    spec.weapon(), "临时 NPC 武器", WEAPONS);
            normalized.add(new KpQuickNpcDTOs.Spec(
                    name, strength, weapon));
        }
        return List.copyOf(normalized);
    }

    @Transactional
    public List<CocCharacter> materialize(
            Long runId,
            List<KpQuickNpcDTOs.Spec> specs) {
        List<KpQuickNpcDTOs.Spec> normalized = validateForRequest(
                runId, specs, Set.of());
        if (normalized.isEmpty()) {
            return List.of();
        }
        Set<String> requiredSkillNames = normalized.stream()
                .map(this::requiredSkillName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<CocSkillDef> definitions = skillDefMapper.selectList(
                new LambdaQueryWrapper<CocSkillDef>()
                        .in(CocSkillDef::getName, requiredSkillNames));
        Map<String, CocSkillDef> definitionsByName = definitions == null
                ? Map.of()
                : definitions.stream().collect(Collectors.toMap(
                CocSkillDef::getName,
                Function.identity(),
                (first, ignored) -> first,
                HashMap::new));
        for (String skillName : requiredSkillNames) {
            CocSkillDef definition = definitionsByName.get(skillName);
            if (definition == null || definition.getBaseValue() == null) {
                throw new UserRequestException(
                        "临时 NPC 所需技能定义不存在：" + skillName);
            }
        }

        List<CocCharacter> created = new ArrayList<>();
        for (KpQuickNpcDTOs.Spec spec : normalized) {
            Tier tier = tier(spec.strength());
            CocCharacter character = character(runId, spec.name(), tier);
            if (characterMapper.insert(character) == 0
                    || character.getId() == null) {
                throw new UserRequestException(
                        "临时 NPC 人物卡创建失败：" + spec.name());
            }
            CocSkillDef skillDefinition = definitionsByName.get(
                    requiredSkillName(spec));
            if (!Objects.equals(
                    skillDefinition.getBaseValue(),
                    tier.combatSkill())) {
                insertSkill(character.getId(), skillDefinition,
                        tier.combatSkill());
            }
            if (!"UNARMED".equals(spec.weapon())) {
                insertWeapon(character.getId(), spec.weapon());
            }
            created.add(character);
        }
        return List.copyOf(created);
    }

    private CocCharacter character(
            Long runId, String name, Tier tier) {
        CharacterCardRules.DerivedValues derived =
                CharacterCardRules.derive(
                        tier.attribute(), tier.attribute(), 65,
                        tier.attribute(), 50, 0);
        LocalDateTime now = LocalDateTime.now();
        return new CocCharacter()
                .setRunId(runId)
                .setActorType("NPC")
                .setParticipantId(null)
                .setName(name)
                .setCreationMethod("QUICK_NPC_TEMPLATE")
                .setStr(tier.attribute())
                .setCon(tier.attribute())
                .setSiz(65)
                .setDex(tier.attribute())
                .setApp(50)
                .setIntValue(50)
                .setPow(50)
                .setEdu(50)
                .setDamageBonus(derived.damageBonus())
                .setBuild(derived.build())
                .setMov(derived.mov())
                .setHpCurrent(derived.hp())
                .setHpMax(derived.hp())
                .setSanCurrent(50)
                .setSanMax(99)
                .setMpCurrent(10)
                .setMpMax(10)
                .setLuckCurrent(null)
                .setArmor(0)
                .setMajorWound(false)
                .setUnconscious(false)
                .setDying(false)
                .setDead(false)
                .setTemporaryInsanity(false)
                .setTemporaryInsanityPhase(null)
                .setTemporaryInsanityRemainingHours(null)
                .setInCover(false)
                .setCoverActionForfeitPending(false)
                .setStunnedRemainingRounds(0)
                .setRestrainedByCharacterId(null)
                .setMeleeAttackedThisRound(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private void insertSkill(
            Long characterId,
            CocSkillDef definition,
            int value) {
        String name = definition.getName();
        int separator = name.indexOf(':');
        CocCharacterSkill skill = new CocCharacterSkill()
                .setCharacterId(characterId)
                .setSkillDefId(definition.getId())
                .setDisplayName(name)
                .setCategory(definition.getCategory())
                .setSpecialization(separator < 0
                        ? "" : name.substring(separator + 1))
                .setBaseValue(definition.getBaseValue())
                .setValue(value)
                .setIsCustom(false);
        if (skillMapper.insert(skill) == 0) {
            throw new UserRequestException(
                    "临时 NPC 技能创建失败：" + name);
        }
    }

    private void insertWeapon(Long characterId, String weaponChoice) {
        CocWeaponCatalogConstant.WeaponDefinition definition =
                CocWeaponCatalogConstant.require(
                        WEAPON_CODES.get(weaponChoice));
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setCharacterId(characterId)
                .setName(definition.name())
                .setSkillName(definition.requiredSkillName())
                .setDamage(definition.damage())
                .setRange(definition.range())
                .setAttacksPerRound(definition.attacksPerRound())
                .setAmmoCapacity(definition.ammoCapacity())
                .setRemainingAmmo(definition.ammoCapacity())
                .setMalfunction(definition.malfunction())
                .setCanImpale(definition.canImpale())
                .setIsBroken(false)
                .setAbnormal(definition.abnormal())
                .setRiskTags(definition.riskTags())
                .setNotes(definition.notes());
        if (weaponMapper.insert(weapon) == 0) {
            throw new UserRequestException(
                    "临时 NPC 武器创建失败：" + definition.name());
        }
    }

    private String requiredSkillName(KpQuickNpcDTOs.Spec spec) {
        return switch (spec.weapon()) {
            case "UNARMED", "LARGE_CLUB", "MEDIUM_KNIFE" -> "斗殴";
            case "PISTOL" -> "射击:手枪";
            case "SMALL_RIFLE", "HUNTING_RIFLE" ->
                    "射击:步枪/霰弹枪";
            default -> throw new UserRequestException(
                    "临时 NPC 武器无效：" + spec.weapon());
        };
    }

    private Tier tier(String strength) {
        return switch (strength) {
            case "WEAK" -> new Tier(50, 25);
            case "MEDIUM" -> new Tier(60, 40);
            case "STRONG" -> new Tier(70, 70);
            default -> throw new UserRequestException(
                    "临时 NPC 强度无效：" + strength);
        };
    }

    private String normalizeName(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new UserRequestException("临时 NPC 名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > 255) {
            throw new UserRequestException(
                    "临时 NPC 名称不能超过255个字符");
        }
        return name;
    }

    private String normalizeChoice(
            String raw, String label, Set<String> allowed) {
        if (!StringUtils.hasText(raw)) {
            throw new UserRequestException(label + "不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new UserRequestException(label + "无效：" + raw.trim());
        }
        return normalized;
    }

    private record Tier(int attribute, int combatSkill) {
    }
}
