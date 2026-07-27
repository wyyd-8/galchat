package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.exception.UserRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
        List<String> completed = new ArrayList<>();
        for (List<DiceRollResult> round : rounds.values()) {
            if (round.stream().anyMatch(result -> result.getResolvedAt() == null)) {
                continue;
            }
            String formatted = formatRound(round);
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
        return resolution.draw() ? "平局" : resolution.winner() + "获胜";
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
            CocCheckOutcome category = CocCheckOutcome.valueOf(
                    stringValue(outcome, "category"));
            return name + switch (category) {
                case CRITICAL_SUCCESS -> "大成功";
                case SUCCESS -> "成功";
                case FAILURE -> "失败";
                case FUMBLE -> "大失败";
            };
        }
        return "";
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
}
