package com.me.galchat.domain.dto;

import com.me.galchat.domain.vo.CharacterCardVO;

import java.util.List;
import java.util.Map;

public final class CharacterCardGenerationModels {

    private CharacterCardGenerationModels() {
    }

    public record CreateRequest(
            Long runId,
            Long participantId,
            String requestId) {
    }

    public record ActionRequest(
            String requestId,
            Integer expectedVersion) {
    }

    public record BuildPlan(
            String name,
            Integer age,
            String sex,
            String birthplace,
            String residence,
            String occupation,
            List<String> attributeOrder,
            List<String> occupationSkillOrder,
            List<String> interestSkillOrder,
            List<String> explanations) {
    }

    public record BuildRolls(
            Integer luck,
            List<List<Integer>> luckRolls,
            List<Integer> educationChecks,
            List<Integer> educationIncreases,
            List<EducationGrowthRoll> educationGrowths) {

        public BuildRolls(
                Integer luck,
                List<Integer> educationChecks,
                List<Integer> educationIncreases) {
            this(luck, null, educationChecks, educationIncreases, null);
        }
    }

    public record EducationGrowthRoll(
            Integer checkRoll,
            Integer increaseRoll) {
    }

    public record BackgroundRolls(
            Integer ideology,
            Integer significantPersonWho,
            Integer significantPersonReason,
            Integer meaningfulLocation,
            Integer treasuredPossession,
            Integer trait,
            Map<String, String> directions) {
    }

    public record BackgroundPlan(
            String appearance,
            String ideology,
            String significantPeople,
            String meaningfulLocations,
            String treasuredPossessions,
            String traits,
            String keyConnectionCategory,
            String keyConnectionText,
            String weaponCode,
            List<String> equipment) {
    }

    public record AvailableWeapon(
            String code,
            String name,
            String skillName,
            Integer skillValue,
            String description) {
    }

    public record DraftState(
            Integer formatVersion,
            BuildPlan buildPlan,
            BuildRolls buildRolls,
            BackgroundRolls backgroundRolls,
            BackgroundPlan backgroundPlan,
            CharacterCardVO preview,
            StepwiseCharacterCardModels.State stepwise) {

        public DraftState(
                Integer formatVersion,
                BuildPlan buildPlan,
                BuildRolls buildRolls,
                BackgroundRolls backgroundRolls,
                BackgroundPlan backgroundPlan,
                CharacterCardVO preview) {
            this(formatVersion, buildPlan, buildRolls, backgroundRolls,
                    backgroundPlan, preview, null);
        }

        public DraftState withBackground(
                BackgroundRolls rolls,
                BackgroundPlan plan,
                CharacterCardVO card) {
            return new DraftState(formatVersion, buildPlan, buildRolls,
                    rolls, plan, card, stepwise);
        }

        public DraftState withStepwise(
                StepwiseCharacterCardModels.State state,
                CharacterCardVO card) {
            return new DraftState(formatVersion, buildPlan, buildRolls,
                    backgroundRolls, backgroundPlan, card, state);
        }
    }

    public record DraftView(
            Long draftId,
            String creationMode,
            String status,
            String currentStep,
            String nextAction,
            Integer version,
            Integer rulesVersion,
            DraftState state) {

        public DraftView(
                Long draftId,
                String creationMode,
                String status,
                String currentStep,
                String nextAction,
                Integer version,
                DraftState state) {
            this(draftId, creationMode, status, currentStep, nextAction,
                    version, null, state);
        }
    }
}
