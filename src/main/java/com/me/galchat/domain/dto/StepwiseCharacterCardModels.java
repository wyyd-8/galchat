package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.CocCharacterWeapon;

import java.util.List;
import java.util.Map;

public final class StepwiseCharacterCardModels {

    private StepwiseCharacterCardModels() {
    }

    public record CreateRequest(
            Long runId,
            Long participantId,
            String name,
            String occupation,
            Integer age,
            String sex,
            String residence,
            String birthplace) {
    }

    public record IdentityUpdateRequest(
            String name,
            String occupation,
            Integer age,
            String sex,
            String residence,
            String birthplace,
            Integer expectedVersion) {
    }

    public record AgeAdjustmentRequest(
            Integer strPenalty,
            Integer conPenalty,
            Integer sizPenalty,
            Integer dexPenalty,
            Integer expectedVersion) {
    }

    public record OccupationRequest(
            String occupation,
            Boolean confirmed,
            Integer expectedVersion) {
    }

    public record SkillAllocation(
            Long skillDefId,
            String specialization,
            Integer allocatedPoints) {
    }

    public record SkillsRequest(
            List<SkillAllocation> allocations,
            Boolean confirmed,
            Integer expectedVersion) {
    }

    public record BackgroundRollRequest(
            String requestId,
            Integer expectedVersion) {
    }

    public record BackgroundRequest(
            Map<String, String> entries,
            String keyConnectionCategory,
            Boolean confirmed,
            Integer expectedVersion) {
    }

    public record EquipmentRequest(
            String era,
            String equipmentText,
            String assetsText,
            String spendingLevel,
            String cash,
            List<WeaponInput> weapons,
            Boolean confirmed,
            Integer expectedVersion) {
    }

    public record WeaponInput(String code) {
    }

    public record Identity(
            Long runId,
            Long participantId,
            String actorType,
            String name,
            String playerName,
            String image,
            String occupation,
            Integer age,
            String sex,
            String residence,
            String birthplace) {
    }

    public record DiceRoll(
            String code,
            String formula,
            List<Integer> dice,
            Integer result) {
    }

    public record EducationGrowth(
            Integer checkRoll,
            Integer increaseRoll,
            Integer eduBefore,
            Integer eduAfter) {
    }

    public record DerivedValues(
            String damageBonus,
            Integer build,
            Integer mov,
            Integer hp,
            Integer san,
            Integer mp) {
    }

    public record Attributes(
            Map<String, Integer> raw,
            List<DiceRoll> rolls,
            List<DiceRoll> luckRolls,
            Integer luck,
            List<EducationGrowth> educationGrowths,
            Map<String, Integer> ageAdjustment,
            Map<String, Integer> finalValues,
            DerivedValues derived) {
    }

    public record Occupation(
            String text,
            Boolean confirmed) {
    }

    public record SkillItem(
            Long skillDefId,
            String displayName,
            String category,
            String specialization,
            Integer baseValue,
            Integer allocatedPoints,
            Integer finalValue,
            Integer halfValue,
            Integer fifthValue) {
    }

    public record Skills(
            Integer budget,
            Integer spent,
            Integer remaining,
            List<SkillItem> items,
            Boolean confirmed) {
    }

    public record BackgroundPrompt(
            String category,
            List<Integer> rolls,
            List<String> promptCodes,
            List<String> prompts) {
    }

    public record Background(
            Map<String, String> entries,
            Map<String, BackgroundPrompt> prompts,
            String keyConnectionCategory,
            String keyConnectionText,
            Boolean confirmed) {
    }

    public record Equipment(
            String era,
            String equipmentText,
            String assetsText,
            String spendingLevel,
            String cash,
            List<CocCharacterWeapon> weapons,
            Boolean confirmed) {
    }

    public record State(
            Identity identity,
            Attributes attributes,
            Occupation occupation,
            Skills skills,
            Background background,
            Equipment equipment) {
    }

    public record AttributeRule(
            String code,
            String formula) {
    }

    public record SkillRule(
            Long skillDefId,
            String name,
            String category,
            Integer baseValue,
            String baseFormula,
            Boolean allowSpecialization,
            String parentName) {
    }

    public record BackgroundRule(
            String code,
            Boolean rollable) {
    }

    public record WeaponRule(
            String code,
            String name,
            String skillName,
            String damage,
            String range,
            String attacksPerRound,
            Integer ammoCapacity,
            String malfunction,
            List<String> eras,
            String kind,
            Boolean canImpale,
            Boolean abnormal,
            List<String> riskTags,
            String notes) {
    }

    public record RulesView(
            Integer rulesVersion,
            List<AttributeRule> attributes,
            List<SkillRule> skills,
            List<BackgroundRule> backgroundCategories,
            List<WeaponRule> weapons,
            List<String> eras) {
    }
}
