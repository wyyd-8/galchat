package com.me.galchat.service.impl.character;

import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.vo.CharacterCardVO;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AutoCharacterCardAssembler {

    private static final List<String> ATTRIBUTES =
            List.of("STR", "CON", "SIZ", "DEX", "APP", "INT", "POW", "EDU");
    private static final List<Integer> QUICK_ATTRIBUTES =
            List.of(80, 70, 60, 60, 50, 50, 50, 40);
    private static final List<Integer> QUICK_OCCUPATION_SKILLS =
            List.of(70, 60, 60, 50, 50, 50, 40, 40, 40);

    private final CharacterSkillResolver skillResolver;

    public AutoCharacterCardAssembler(CharacterSkillResolver skillResolver) {
        this.skillResolver = skillResolver;
    }

    public CharacterCardGenerationModels.DraftState build(
            CharacterTemplate template,
            CocModule module,
            CharacterCardGenerationModels.BuildPlan plan,
            List<CocSkillDef> definitions,
            CharacterCardGenerationModels.BuildRolls rolls) {
        requireBuildInputs(template, plan, definitions, rolls);
        Map<String, Integer> attributes = assignAttributes(plan.attributeOrder());
        applyAge(attributes, plan.age(), rolls);
        CocCharacter character = character(template, module, plan, attributes, rolls.luck());
        List<CocCharacterSkill> skills = skillResolver.normalizeOverrides(
                character, assignSkills(character, plan, definitions), definitions);
        CharacterCardVO card = new CharacterCardVO(
                character, skills, List.of(), new CocCharacterProfile());
        return new CharacterCardGenerationModels.DraftState(
                1, plan, rolls, null, null, card);
    }

    public CharacterCardGenerationModels.DraftState applyBackground(
            CharacterCardGenerationModels.DraftState state,
            CharacterCardGenerationModels.BackgroundPlan plan,
            CharacterCardGenerationModels.BackgroundRolls rolls,
            List<CocCharacterSkill> effectiveSkills) {
        if (state == null || state.preview() == null || plan == null || rolls == null) {
            throw new IllegalArgumentException("背景生成数据不完整");
        }
        Map<String, String> backgroundEntries = backgroundEntries(plan);
        String keyCategory = normalize(plan.keyConnectionCategory());
        String keyConnection = keyCategory == null
                ? null : backgroundEntries.get(keyCategory.toUpperCase());
        if (keyConnection == null || "APPEARANCE".equalsIgnoreCase(keyCategory)) {
            throw new IllegalArgumentException("关键背景连接必须指向已有背景条目");
        }
        List<String> equipment = normalizeEquipment(plan.equipment());
        CocCharacterProfile profile = new CocCharacterProfile()
                .setAppearance(backgroundEntries.get("APPEARANCE"))
                .setIdeology(backgroundEntries.get("IDEOLOGY"))
                .setSignificantPeople(backgroundEntries.get("SIGNIFICANT_PEOPLE"))
                .setMeaningfulLocations(backgroundEntries.get("MEANINGFUL_LOCATIONS"))
                .setTreasuredPossessions(backgroundEntries.get("TREASURED_POSSESSIONS"))
                .setTraits(backgroundEntries.get("TRAITS"))
                .setKeyConnectionCategory(keyCategory.toUpperCase())
                .setKeyConnectionText(keyConnection)
                .setEquipmentText(equipment.isEmpty() ? null : String.join("\n", equipment));
        List<CocCharacterWeapon> weapons = weapon(
                state.preview(), effectiveSkills, plan.weaponCode());
        CharacterCardVO preview = new CharacterCardVO(
                state.preview().getCharacter(), state.preview().getSkills(),
                weapons, profile);
        return state.withBackground(rolls, plan, preview);
    }

    private Map<String, String> backgroundEntries(
            CharacterCardGenerationModels.BackgroundPlan plan) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("APPEARANCE", normalize(plan.appearance()));
        entries.put("IDEOLOGY", normalize(plan.ideology()));
        entries.put("SIGNIFICANT_PEOPLE", normalize(plan.significantPeople()));
        entries.put("MEANINGFUL_LOCATIONS", normalize(plan.meaningfulLocations()));
        entries.put("TREASURED_POSSESSIONS", normalize(plan.treasuredPossessions()));
        entries.put("TRAITS", normalize(plan.traits()));
        if (entries.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("AI必须填写全部六项人物背景");
        }
        return entries;
    }

    private void requireBuildInputs(
            CharacterTemplate template,
            CharacterCardGenerationModels.BuildPlan plan,
            List<CocSkillDef> definitions,
            CharacterCardGenerationModels.BuildRolls rolls) {
        if (template == null || plan == null || definitions == null || rolls == null) {
            throw new IllegalArgumentException("人物卡生成数据不完整");
        }
        if (normalize(plan.name()) == null) {
            throw new IllegalArgumentException("AI必须为新调查员生成姓名");
        }
        if (plan.age() == null || plan.age() < 15 || plan.age() > 90) {
            throw new IllegalArgumentException("调查员年龄必须在15到90岁之间");
        }
    }

    private Map<String, Integer> assignAttributes(List<String> order) {
        if (order == null || order.size() != ATTRIBUTES.size()
                || !new HashSet<>(order).equals(new HashSet<>(ATTRIBUTES))) {
            throw new IllegalArgumentException("属性排序必须完整且不能重复");
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        for (int index = 0; index < order.size(); index++) {
            values.put(order.get(index), QUICK_ATTRIBUTES.get(index));
        }
        return values;
    }

    private void applyAge(
            Map<String, Integer> values,
            int age,
            CharacterCardGenerationModels.BuildRolls rolls) {
        int physicalPenalty = 0;
        int appPenalty = 0;
        int growthChecks;
        if (age < 20) {
            String target = values.get("STR") <= values.get("SIZ") ? "STR" : "SIZ";
            values.compute(target, (key, value) -> Math.max(0, value - 5));
            values.compute("EDU", (key, value) -> Math.max(0, value - 5));
            growthChecks = 0;
        } else if (age < 40) {
            growthChecks = 1;
        } else if (age < 50) {
            physicalPenalty = 5;
            appPenalty = 5;
            growthChecks = 2;
        } else if (age < 60) {
            physicalPenalty = 10;
            appPenalty = 10;
            growthChecks = 3;
        } else if (age < 70) {
            physicalPenalty = 20;
            appPenalty = 15;
            growthChecks = 4;
        } else if (age < 80) {
            physicalPenalty = 40;
            appPenalty = 20;
            growthChecks = 4;
        } else {
            physicalPenalty = 80;
            appPenalty = 25;
            growthChecks = 4;
        }
        values.put("APP", Math.max(0, values.get("APP") - appPenalty));
        distributePenalty(values, physicalPenalty);
        applyEducationGrowth(values, growthChecks, rolls);
    }

    private void distributePenalty(Map<String, Integer> values, int penalty) {
        for (String name : List.of("STR", "CON", "DEX")) {
            if (penalty == 0) {
                return;
            }
            int deduction = Math.min(values.get(name), penalty);
            values.put(name, values.get(name) - deduction);
            penalty -= deduction;
        }
        if (penalty > 0) {
            throw new IllegalArgumentException("年龄属性减值无法分配");
        }
    }

    private void applyEducationGrowth(
            Map<String, Integer> values,
            int count,
            CharacterCardGenerationModels.BuildRolls rolls) {
        List<Integer> checks = rolls.educationChecks() == null
                ? List.of() : rolls.educationChecks();
        List<Integer> increases = rolls.educationIncreases() == null
                ? List.of() : rolls.educationIncreases();
        int increaseIndex = 0;
        for (int index = 0; index < count; index++) {
            if (index >= checks.size()) {
                throw new IllegalArgumentException("教育成长骰点不足");
            }
            if (checks.get(index) > values.get("EDU")) {
                if (increaseIndex >= increases.size()) {
                    throw new IllegalArgumentException("教育成长增量骰点不足");
                }
                values.put("EDU", Math.min(99,
                        values.get("EDU") + increases.get(increaseIndex++)));
            }
        }
    }

    private CocCharacter character(
            CharacterTemplate template,
            CocModule module,
            CharacterCardGenerationModels.BuildPlan plan,
            Map<String, Integer> values,
            int luck) {
        CharacterCardRules.DerivedValues derived = CharacterCardRules.derive(
                values.get("STR"), values.get("CON"), values.get("SIZ"),
                values.get("DEX"), values.get("POW"), plan.age());
        return new CocCharacter()
                .setActorType("BOT")
                .setParticipantId(template.getId())
                .setName(normalize(plan.name()))
                .setPlayerName(template.getName())
                .setImage(template.getImage())
                .setOccupation(normalize(plan.occupation()))
                .setSex(normalize(plan.sex()))
                .setAge(plan.age())
                .setEra(module == null ? null : module.getEra())
                .setBirthplace(normalize(plan.birthplace()))
                .setResidence(normalize(plan.residence()))
                .setCreationMethod("AUTO_QUICK_START")
                .setStr(values.get("STR")).setCon(values.get("CON"))
                .setSiz(values.get("SIZ")).setDex(values.get("DEX"))
                .setApp(values.get("APP")).setIntValue(values.get("INT"))
                .setPow(values.get("POW")).setEdu(values.get("EDU"))
                .setDamageBonus(derived.damageBonus()).setBuild(derived.build())
                .setMov(derived.mov())
                .setHpCurrent(derived.hp()).setHpMax(derived.hp())
                .setSanCurrent(derived.san()).setSanMax(99)
                .setMpCurrent(derived.mp()).setMpMax(derived.mp())
                .setLuckCurrent(luck).setArmor(0)
                .setMajorWound(false).setUnconscious(false).setDying(false)
                .setDead(false).setTemporaryInsanity(false)
                .setInCover(false)
                .setCoverActionForfeitPending(false)
                .setStunnedRemainingRounds(0)
                .setRestrainedByCharacterId(null)
                .setMeleeAttackedThisRound(false);
    }

    private List<CocCharacterSkill> assignSkills(
            CocCharacter character,
            CharacterCardGenerationModels.BuildPlan plan,
            List<CocSkillDef> definitions) {
        Map<String, CocSkillDef> byName = skillResolver.definitionsByName(definitions);
        Map<String, CocCharacterSkill> skills = new LinkedHashMap<>();
        for (CocSkillDef definition : definitions) {
            Integer base = skillResolver.resolveDefaultValue(definition, character);
            if (Boolean.TRUE.equals(definition.getIsCore()) && base != null) {
                skills.put(definition.getName(), skill(definition, base));
            }
        }
        List<String> occupation = normalizeSkillNames(
                plan.occupationSkillOrder(), byName);
        List<String> interestOrder = normalizeSkillNames(
                plan.interestSkillOrder(), byName);
        if (occupation == null || occupation.size() != 9
                || new HashSet<>(occupation).size() != 9) {
            throw new IllegalArgumentException("本职技能排序必须包含9项且不能重复");
        }
        if (occupation.contains("克苏鲁神话")
                || (interestOrder != null
                && interestOrder.contains("克苏鲁神话"))) {
            throw new IllegalArgumentException("快速开始不能为克苏鲁神话分配技能值");
        }
        for (int index = 0; index < occupation.size(); index++) {
            CocCharacterSkill selected = requireSkill(
                    skills, byName, occupation.get(index), character);
            selected.setValue(Math.max(selected.getBaseValue(),
                    QUICK_OCCUPATION_SKILLS.get(index)));
        }
        CocCharacterSkill creditRating = requireSkill(
                skills, byName, "信用评级", character);
        creditRating.setValue(Math.max(creditRating.getValue(), 10));
        Set<String> occupationNames = Set.copyOf(occupation);
        LinkedHashSet<String> interests = new LinkedHashSet<>();
        if (interestOrder != null) {
            interestOrder.stream()
                    .filter(name -> !occupationNames.contains(name))
                    .filter(name -> !"信用评级".equals(name))
                    .forEach(interests::add);
        }
        if (interests.size() < 4) {
            throw new IllegalArgumentException("至少需要4项非本职技能");
        }
        interests.stream().limit(4).forEach(name -> {
            CocCharacterSkill selected = requireSkill(skills, byName, name, character);
            selected.setValue(Math.min(99, selected.getBaseValue() + 20));
        });
        return List.copyOf(skills.values());
    }

    private List<String> normalizeSkillNames(
            List<String> names, Map<String, CocSkillDef> definitions) {
        return names == null ? null : names.stream()
                .map(name -> skillResolver.resolveCanonicalSkillName(
                        name, definitions))
                .toList();
    }

    private CocCharacterSkill requireSkill(
            Map<String, CocCharacterSkill> skills,
            Map<String, CocSkillDef> definitions,
            String name,
            CocCharacter character) {
        CocCharacterSkill existing = skills.get(name);
        if (existing != null) {
            return existing;
        }
        CocSkillDef definition = definitions.get(name);
        Integer base = skillResolver.resolveDefaultValue(definition, character);
        if (definition == null || base == null) {
            throw new IllegalArgumentException("未知或不可分配技能：" + name);
        }
        CocCharacterSkill created = skill(definition, base);
        skills.put(name, created);
        return created;
    }

    private CocCharacterSkill skill(CocSkillDef definition, int base) {
        String name = definition.getName();
        String specialization = name.contains(":")
                ? name.substring(name.indexOf(':') + 1) : "";
        return new CocCharacterSkill()
                .setSkillDefId(definition.getId())
                .setDisplayName(name)
                .setCategory(definition.getCategory())
                .setSpecialization(specialization)
                .setBaseValue(base).setValue(base).setIsCustom(false);
    }

    private List<CocCharacterWeapon> weapon(
            CharacterCardVO card,
            List<CocCharacterSkill> effectiveSkills,
            String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        CocWeaponCatalogConstant.WeaponDefinition definition =
                CocWeaponCatalogConstant.require(code);
        if (!definition.autoSelectable()) {
            throw new IllegalArgumentException(
                    "受管制武器不能自动成为初始武器");
        }
        boolean available = CocWeaponCatalogConstant.availableForEra(
                        card.getCharacter().getEra()).stream()
                .anyMatch(candidate -> candidate.code().equals(code));
        if (!available) {
            throw new IllegalArgumentException("武器不适用于当前时代");
        }
        boolean hasSkill = effectiveSkills != null && effectiveSkills.stream().anyMatch(
                skill -> definition.requiredSkillName().equals(skill.getDisplayName()));
        if (!hasSkill) {
            throw new IllegalArgumentException("人物卡缺少武器所需技能");
        }
        return List.of(new CocCharacterWeapon()
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
                .setNotes(definition.notes()));
    }

    private List<String> normalizeEquipment(List<String> items) {
        if (items == null) {
            return List.of();
        }
        List<String> normalized = items.stream()
                .map(this::normalize)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (normalized.size() > 5) {
            throw new IllegalArgumentException("装备不能超过5件");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
