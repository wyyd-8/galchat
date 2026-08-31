package com.me.galchat.domain.vo;

import com.me.galchat.constant.CocCheckDifficulty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class DiceResolutionDataVO {
    private Integer version = 1;
    private String type;
    private Long sourceResultId;
    private Map<String, Object> rule = new LinkedHashMap<>();
    private Map<String, Object> outcome;
    private Map<String, Object> effect;

    public static DiceResolutionDataVO pending(
            String type, Long sourceResultId, Map<String, Object> rule) {
        return new DiceResolutionDataVO()
                .setType(type)
                .setSourceResultId(sourceResultId)
                .setRule(new LinkedHashMap<>(rule));
    }

    public DiceResolutionVO publicView() {
        Object savedGroupRule = rule == null ? null : rule.get("groupRule");
        Object savedCharacterName = rule == null ? null : rule.get("characterName");
        Object savedCheckName = rule == null ? null : rule.get("checkName");
        Object savedDifficulty = rule == null ? null : rule.get("difficulty");
        Object savedTargetValue = rule == null ? null : rule.get("targetValue");
        List<Map<String, Object>> savedModifierFactors = publicModifierFactors(
                rule == null ? null : rule.get("modifierFactors"));
        return new DiceResolutionVO(
                type,
                sourceResultId,
                savedGroupRule instanceof String value ? value : null,
                savedCharacterName instanceof String value ? value : null,
                savedCheckName instanceof String value ? value : null,
                savedDifficulty instanceof String value ? value : null,
                effectiveTargetValue(
                        savedTargetValue,
                        savedDifficulty,
                        rule == null ? null : rule.get("difficultyIncrease")),
                savedModifierFactors,
                outcome,
                effect);
    }

    private static List<Map<String, Object>> publicModifierFactors(Object savedFactors) {
        if (!(savedFactors instanceof List<?> factors)) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object savedFactor : factors) {
            if (!(savedFactor instanceof Map<?, ?> factor)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            factor.forEach((key, value) -> copy.put(String.valueOf(key), value));
            result.add(copy);
        }
        return result.isEmpty() ? null : result;
    }

    private static Integer effectiveTargetValue(
            Object savedTargetValue,
            Object savedDifficulty,
            Object savedDifficultyIncrease) {
        if (!(savedTargetValue instanceof Number value)) {
            return null;
        }
        int targetValue = value.intValue();
        if (savedDifficultyIncrease instanceof Number increase) {
            int levels = increase.intValue();
            if (levels >= 3) {
                return 1;
            }
            CocCheckDifficulty difficulty = levels == 1
                    ? CocCheckDifficulty.HARD
                    : levels == 2
                    ? CocCheckDifficulty.EXTREME
                    : CocCheckDifficulty.REGULAR;
            return Math.max(1, difficulty.requiredThreshold(targetValue));
        }
        if ("CRITICAL".equals(savedDifficulty)) {
            return 1;
        }
        if (savedDifficulty instanceof String difficultyName) {
            try {
                CocCheckDifficulty difficulty = CocCheckDifficulty.valueOf(difficultyName);
                return Math.max(1, difficulty.requiredThreshold(targetValue));
            } catch (IllegalArgumentException ignored) {
                // Preserve legacy values with an unknown difficulty label.
            }
        }
        return targetValue;
    }
}
