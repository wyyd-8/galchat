package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.exception.UserRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class CocDiceSummaryFormatter {

    public String rebuildTotalResult(List<DiceRollResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        Map<Integer, List<DiceRollResult>> rounds = new TreeMap<>();
        for (DiceRollResult result : results) {
            rounds.computeIfAbsent(result.getRoundNo(), ignored -> new ArrayList<>()).add(result);
        }
        Set<Long> combinedSanLossSources = completedInsanitySourceIds(results);
        Set<Long> combinedDamageSources = completedMajorWoundSourceIds(results);
        List<String> completed = new ArrayList<>();
        for (List<DiceRollResult> round : rounds.values()) {
            if (round.stream().anyMatch(result -> result.getResolvedAt() == null)) {
                continue;
            }
            List<DiceRollResult> visibleRound = round.stream()
                    .filter(result -> !DiceRollConstant.TYPE_SAN_LOSS.equals(
                            requireResolution(result).getType())
                            || result.getId() == null
                            || !combinedSanLossSources.contains(result.getId()))
                    .filter(result -> !DiceRollConstant.TYPE_DAMAGE.equals(
                            requireResolution(result).getType())
                            || result.getId() == null
                            || !combinedDamageSources.contains(result.getId()))
                    .toList();
            if (visibleRound.isEmpty()) {
                continue;
            }
            String formatted = formatRound(visibleRound);
            if (formatted != null && !formatted.isBlank()) {
                completed.add(formatted);
            }
        }
        return completed.isEmpty() ? null : String.join("\n", completed);
    }

    public String formatRound(List<DiceRollResult> completedRound) {
        if (completedRound == null || completedRound.isEmpty()) {
            return "";
        }
        List<DiceRollResult> ordered = completedRound.stream()
                .sorted(Comparator
                        .comparing(DiceRollResult::getDisplayOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DiceRollResult::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (ordered.stream().anyMatch(result -> result.getResolvedAt() == null)) {
            throw new UserRequestException("存在尚未结算的掷骰结果");
        }
        if (ordered.stream().allMatch(result -> DiceRollConstant.TYPE_OPPOSED_CHECK.equals(
                requireResolution(result).getType()))) {
            return formatOpposed(ordered);
        }
        if (ordered.stream().allMatch(this::isInsanityResult)) {
            return formatInsanityRound(ordered);
        }
        if (ordered.stream().allMatch(result ->
                DiceRollConstant.TYPE_MAJOR_WOUND_CON.equals(
                        requireResolution(result).getType()))) {
            return formatMajorWoundRound(ordered);
        }
        return ordered.stream()
                .map(this::formatIndividual)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private String formatOpposed(List<DiceRollResult> results) {
        List<CocDiceRules.OpposedCandidate> candidates = results.stream()
                .map(result -> {
                    Map<String, Object> rule = requireResolution(result).getRule();
                    return new CocDiceRules.OpposedCandidate(
                            stringValue(rule, "characterName"),
                            intValue(rule, "targetValue"),
                            requireRoll(result));
                })
                .toList();
        String tieWinner = nullableString(
                requireResolution(results.getFirst()).getRule().get("tieWinnerCharacterName"));
        CocDiceRules.OpposedResolution resolution =
                CocDiceRules.resolveOpposed(candidates, tieWinner);
        if (resolution.winner() == null && !resolution.draw()) {
            return results.stream()
                    .map(this::formatOpposedFailure)
                    .collect(Collectors.joining("；"));
        }
        List<String> formatted = new ArrayList<>();
        formatted.add(resolution.draw() ? "平局" : resolution.winner() + "获胜");
        results.stream()
                .map(this::formatOpposedExceptionalOutcome)
                .filter(Objects::nonNull)
                .forEach(formatted::add);
        return String.join("；", formatted);
    }

    private String formatOpposedFailure(DiceRollResult result) {
        Map<String, Object> outcome = requireResolution(result).getOutcome();
        String name = stringValue(outcome, "characterName");
        CocCheckOutcome category = CocCheckOutcome.valueOf(
                stringValue(outcome, "category"));
        return name + (category == CocCheckOutcome.FUMBLE ? "大失败" : "失败");
    }

    private String formatOpposedExceptionalOutcome(DiceRollResult result) {
        Map<String, Object> outcome = requireResolution(result).getOutcome();
        CocCheckOutcome category = CocCheckOutcome.valueOf(
                stringValue(outcome, "category"));
        return switch (category) {
            case CRITICAL_SUCCESS -> stringValue(outcome, "characterName") + "大成功";
            case FUMBLE -> stringValue(outcome, "characterName") + "大失败";
            case SUCCESS, FAILURE -> null;
        };
    }

    private String formatIndividual(DiceRollResult result) {
        DiceResolutionDataVO resolution = requireResolution(result);
        if (DiceRollConstant.TYPE_CHECK.equals(resolution.getType())
                || DiceRollConstant.TYPE_SAN_CHECK.equals(resolution.getType())) {
            Map<String, Object> outcome = resolution.getOutcome();
            if (outcome == null) {
                return "";
            }
            String name = stringValue(outcome, "characterName");
            String checkName = stringValue(resolution.getRule(), "checkName");
            CocCheckOutcome category = CocCheckOutcome.valueOf(
                    stringValue(outcome, "category"));
            return name + "进行“" + checkName + "”检定："
                    + checkOutcomeLabel(category, outcome);
        }
        if (DiceRollConstant.TYPE_UNCONSCIOUS_RECOVERY_CON.equals(
                resolution.getType())) {
            Map<String, Object> outcome = resolution.getOutcome();
            Map<String, Object> effect = resolution.getEffect();
            if (outcome == null || effect == null) {
                return "";
            }
            boolean awake = !booleanValue(effect, "unconscious");
            return stringValue(outcome, "characterName")
                    + "CON检定" + (awake
                    ? "成功，脱离昏迷" : "失败，仍处于昏迷");
        }
        if (DiceRollConstant.TYPE_SAN_LOSS.equals(resolution.getType())) {
            Map<String, Object> effect = resolution.getEffect();
            if (effect == null) {
                return "";
            }
            return stringValue(resolution.getRule(), "characterName")
                    + "理智-" + intValue(effect, "sanLoss");
        }
        if (DiceRollConstant.TYPE_DAMAGE.equals(resolution.getType())) {
            Map<String, Object> effect = resolution.getEffect();
            if (effect == null) {
                return "";
            }
            StringBuilder text = new StringBuilder()
                    .append(stringValue(resolution.getRule(), "characterName"))
                    .append("生命-")
                    .append(intValue(effect, "hpLoss"));
            if (booleanValue(effect, "majorWoundChanged")) {
                text.append("；受到重伤");
            }
            if (booleanValue(effect, "unconscious")) {
                text.append("；陷入昏迷");
            }
            return text.toString();
        }
        if (DiceRollConstant.TYPE_HEALING.equals(resolution.getType())) {
            Map<String, Object> effect = resolution.getEffect();
            if (effect == null) {
                return "";
            }
            StringBuilder text = new StringBuilder()
                    .append(stringValue(resolution.getRule(), "characterName"))
                    .append("生命+")
                    .append(intValue(effect, "hpGain"));
            if (Boolean.TRUE.equals(effect.get("majorWoundChanged"))) {
                text.append("；解除重伤");
            }
            if (Boolean.TRUE.equals(effect.get("unconsciousChanged"))) {
                text.append("；脱离昏迷");
            }
            return text.toString();
        }
        return "";
    }

    private String formatMajorWoundRound(List<DiceRollResult> results) {
        return results.stream().map(result -> {
            DiceResolutionDataVO resolution = requireResolution(result);
            Map<String, Object> outcome = resolution.getOutcome();
            Map<String, Object> effect = resolution.getEffect();
            if (outcome == null || effect == null) {
                throw new UserRequestException("重伤CON检定尚未完成结算");
            }
            CocCheckOutcome category = CocCheckOutcome.valueOf(
                    stringValue(outcome, "category"));
            boolean success = category == CocCheckOutcome.CRITICAL_SUCCESS
                    || category == CocCheckOutcome.SUCCESS;
            return stringValue(resolution.getRule(), "characterName")
                    + "生命-" + intValue(resolution.getRule(), "hpLoss")
                    + "；受到重伤；CON检定"
                    + (success ? "成功，保持清醒" : "失败，陷入昏迷");
        }).collect(Collectors.joining("；"));
    }

    private String formatInsanityRound(List<DiceRollResult> results) {
        Map<Long, List<DiceRollResult>> pairs = results.stream()
                .collect(Collectors.groupingBy(
                        result -> {
                            Long source = requireResolution(result).getSourceResultId();
                            if (source == null) {
                                throw new UserRequestException("临时疯狂结果缺少来源");
                            }
                            return source;
                        },
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<String> formatted = new ArrayList<>();
        for (List<DiceRollResult> pair : pairs.values()) {
            DiceRollResult type = pair.stream()
                    .filter(result -> DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(
                            requireResolution(result).getType()))
                    .findFirst()
                    .orElseThrow(() -> new UserRequestException("临时疯狂类型结果不存在"));
            DiceRollResult duration = pair.stream()
                    .filter(result -> DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION.equals(
                            requireResolution(result).getType()))
                    .findFirst()
                    .orElseThrow(() -> new UserRequestException("临时疯狂持续时间结果不存在"));
            DiceResolutionDataVO typeResolution = requireResolution(type);
            DiceResolutionDataVO durationResolution = requireResolution(duration);
            if (typeResolution.getOutcome() == null
                    || durationResolution.getOutcome() == null
                    || durationResolution.getEffect() == null) {
                throw new UserRequestException("临时疯狂结果尚未完成结算");
            }
            formatted.add(stringValue(typeResolution.getRule(), "characterName")
                    + "理智-" + intValue(typeResolution.getRule(), "sanLoss")
                    + "；进入临时疯狂："
                    + stringValue(typeResolution.getOutcome(), "display")
                    + "，持续"
                    + intValue(durationResolution.getOutcome(), "durationHours")
                    + "小时");
        }
        return String.join("；", formatted);
    }

    private Set<Long> completedInsanitySourceIds(List<DiceRollResult> results) {
        Map<Long, List<DiceRollResult>> pairs = new LinkedHashMap<>();
        for (DiceRollResult result : results) {
            if (!isInsanityResult(result)) {
                continue;
            }
            Long source = requireResolution(result).getSourceResultId();
            if (source != null) {
                pairs.computeIfAbsent(source, ignored -> new ArrayList<>()).add(result);
            }
        }
        return pairs.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(result ->
                        DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(
                                requireResolution(result).getType())
                                && result.getResolvedAt() != null))
                .filter(entry -> entry.getValue().stream().anyMatch(result ->
                        DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION.equals(
                                requireResolution(result).getType())
                                && result.getResolvedAt() != null
                                && requireResolution(result).getEffect() != null))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private Set<Long> completedMajorWoundSourceIds(List<DiceRollResult> results) {
        return results.stream()
                .filter(result -> DiceRollConstant.TYPE_MAJOR_WOUND_CON.equals(
                        requireResolution(result).getType()))
                .filter(result -> result.getResolvedAt() != null)
                .filter(result -> requireResolution(result).getEffect() != null)
                .map(result -> requireResolution(result).getSourceResultId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean isInsanityResult(DiceRollResult result) {
        String type = requireResolution(result).getType();
        return DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(type)
                || DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION.equals(type);
    }

    private DiceResolutionDataVO requireResolution(DiceRollResult result) {
        if (result == null || result.getResolutionData() == null) {
            throw new UserRequestException("掷骰结算数据不存在");
        }
        return result.getResolutionData();
    }

    private int requireRoll(DiceRollResult result) {
        if (result.getResultData() == null || result.getResultData().getResult() == null) {
            throw new UserRequestException("掷骰结果尚未完成");
        }
        return result.getResultData().getResult();
    }

    private int intValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new UserRequestException("掷骰规则字段无效：" + key);
    }

    private boolean booleanValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new UserRequestException("掷骰规则字段无效：" + key);
    }

    private String stringValue(Map<String, Object> values, String key) {
        String value = nullableString(values.get(key));
        if (value == null || value.isBlank()) {
            throw new UserRequestException("掷骰规则字段无效：" + key);
        }
        return value;
    }

    private String nullableString(Object value) {
        return Objects.toString(value, null);
    }

    private String checkOutcomeLabel(
            CocCheckOutcome category, Map<String, Object> outcome) {
        return switch (category) {
            case CRITICAL_SUCCESS -> "大成功";
            case FAILURE -> "失败";
            case FUMBLE -> "大失败";
            case SUCCESS -> {
                String savedRank = nullableString(outcome.get("rank"));
                if (savedRank == null || savedRank.isBlank()) {
                    yield "成功";
                }
                yield switch (CocDiceRules.CheckRank.valueOf(savedRank)) {
                    case CRITICAL -> "大成功";
                    case EXTREME -> "极难成功";
                    case HARD -> "困难成功";
                    case REGULAR -> "常规成功";
                    case FAILURE, FUMBLE -> throw new UserRequestException(
                            "掷骰结果成功等级无效：" + savedRank);
                };
            }
        };
    }
}
