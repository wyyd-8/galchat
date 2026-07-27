package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.domain.vo.DiceRollAggregate;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.DiceFollowUpLocator;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.ICocDiceOrchestrationService;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.utils.DiceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CocDiceOrchestrationService implements ICocDiceOrchestrationService {

    private final IDiceRollInternalService internalService;
    private final ICharacterCardService characterCardService;
    private final GroupConversationService conversationService;
    private final DiceFollowUpLocator followUpLocator;
    private final CocDiceSummaryFormatter summaryFormatter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Check request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        CocCheckDifficulty difficulty = Objects.requireNonNullElse(
                request.difficulty(), CocCheckDifficulty.REGULAR);
        List<KpDiceRequestDTOs.CheckTarget> targets =
                requireTargets(request.targets(), false);
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            KpDiceRequestDTOs.CheckTarget target = targets.get(index);
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, target.characterName().trim());
            int targetValue = requireCheckValue(card, target.checkName());
            CocPercentileModifier modifier = normalizeModifier(target.modifier());
            drafts.add(checkDraft(
                    card,
                    target.checkName().trim(),
                    targetValue,
                    difficulty,
                    modifier,
                    false,
                    DiceRollConstant.TYPE_CHECK,
                    request.reason(),
                    index + 1,
                    null));
        }
        return createAndSettle(conversationId, request.reason(), drafts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestOpposedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Opposed request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        List<KpDiceRequestDTOs.CheckTarget> targets =
                requireTargets(request.targets(), true);
        Set<String> targetNames = targets.stream()
                .map(target -> target.characterName().trim())
                .collect(java.util.stream.Collectors.toSet());
        String tieWinner = trimToNull(request.tieWinnerCharacterName());
        if (tieWinner != null && !targetNames.contains(tieWinner)) {
            throw new UserRequestException("平局胜者必须属于对抗检定角色");
        }

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            KpDiceRequestDTOs.CheckTarget target = targets.get(index);
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, target.characterName().trim());
            int targetValue = requireCheckValue(card, target.checkName());
            drafts.add(checkDraft(
                    card,
                    target.checkName().trim(),
                    targetValue,
                    CocCheckDifficulty.REGULAR,
                    normalizeModifier(target.modifier()),
                    false,
                    DiceRollConstant.TYPE_OPPOSED_CHECK,
                    request.reason(),
                    index + 1,
                    tieWinner));
        }
        return createAndSettle(conversationId, request.reason(), drafts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestPushedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Pushed request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        List<String> names = requireCharacterNames(request.characterNames());
        Long summaryId = followUpLocator.requireLatestSummaryId(
                conversationId, Set.of(DiceRollConstant.TOOL_REQUEST_CHECK));
        DiceRollSummary summary = internalService.requireSummaryForUpdate(summaryId);
        requireConversation(summary, conversationId);
        if (!DiceRollConstant.STATUS_COMPLETED.equals(summary.getStatus())) {
            throw new UserRequestException("前一轮检定尚未完成");
        }

        List<DiceRollResult> existing = internalService.listResultEntities(summaryId);
        int previousRound = summary.getRoundCount();
        List<DiceRollResult> previousChecks = safeResults(existing).stream()
                .filter(result -> Objects.equals(previousRound, result.getRoundNo()))
                .filter(result -> DiceRollConstant.TYPE_CHECK.equals(
                        resolution(result).getType()))
                .toList();
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(names.size());
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            DiceRollResult previous = previousChecks.stream()
                    .filter(result -> name.equals(ruleString(result, "characterName")))
                    .findFirst()
                    .orElseThrow(() -> new UserRequestException(
                            "找不到角色“" + name + "”的前一次检定"));
            if (!CocCheckOutcome.FAILURE.name().equals(outcomeString(previous, "category"))) {
                throw new UserRequestException("只有前一次检定失败才能孤注一掷");
            }
            Map<String, Object> previousRule = resolution(previous).getRule();
            Map<String, Object> pushedRule = new LinkedHashMap<>(previousRule);
            pushedRule.put("pushed", true);
            DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
            draft.setCharacterId(previous.getCharacterId());
            draft.setDisplayOrder(index + 1);
            draft.setDisplayType(DiceRollConstant.TYPE_CHECK);
            draft.setReason(request.reason().trim());
            draft.setFormula(CocPercentileModifier.valueOf(
                    stringValue(previousRule, "modifier")).formula());
            draft.setResolutionData(DiceResolutionDataVO.pending(
                    DiceRollConstant.TYPE_CHECK, previous.getId(), pushedRule));
            drafts.add(draft);
        }

        List<DiceRollResult> created = internalService.appendDiceRollRound(
                conversationId, summaryId, drafts);
        int nextRound = created.stream()
                .map(DiceRollResult::getRoundNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(previousRound + 1);
        summary.setRoundCount(Math.max(previousRound, nextRound));
        settleAlreadyRolled(created);
        List<DiceRollResult> allResults = mergeResults(existing, created);
        refreshSummary(summary, allResults);
        return toolResult(summary, created);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestSanCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.SanCheck request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        List<String> names = requireCharacterNames(request.characterNames());
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(names.size());
        for (int index = 0; index < names.size(); index++) {
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, names.get(index));
            if (card.sanCurrent() == null || card.sanCurrent() < 1
                    || card.sanCurrent() > 100) {
                throw new UserRequestException("角色“" + card.name() + "”的当前理智值无法检定");
            }
            drafts.add(checkDraft(
                    card,
                    "理智",
                    card.sanCurrent(),
                    CocCheckDifficulty.REGULAR,
                    CocPercentileModifier.NORMAL,
                    false,
                    DiceRollConstant.TYPE_SAN_CHECK,
                    request.reason(),
                    index + 1,
                    null));
        }
        return createAndSettle(conversationId, request.reason(), drafts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollProgressVO rollPlayerResult(Long resultId) {
        DiceRollResult initial = internalService.requireResult(resultId);
        DiceRollSummary summary = internalService.requireSummaryForUpdate(initial.getSummaryId());
        conversationService.requireActive(summary.getConversationId());
        DiceRollResult result = internalService.requireResult(resultId);
        if (!summary.getId().equals(result.getSummaryId())) {
            throw new UserRequestException("掷骰结果不存在");
        }
        if (result.getCharacterId() != null) {
            throw new UserRequestException("该结果不是玩家掷骰位置");
        }
        if (result.getResolvedAt() != null) {
            return new DiceRollProgressVO(
                    DiceRollSummaryVO.from(summary),
                    DiceRollDetailVO.from(result),
                    List.of());
        }
        if (!Objects.equals(summary.getRoundCount(), result.getRoundNo())) {
            throw new UserRequestException("该结果不属于当前掷骰轮次");
        }
        DiceRollResultVO placeholder = result.getResultData();
        if (placeholder == null || !StringUtils.hasText(placeholder.getFormula())
                || placeholder.getResult() != null
                || placeholder.getModules() == null
                || placeholder.getModules().isEmpty()) {
            throw new UserRequestException("该位置不是待完成的玩家掷骰");
        }

        result.setResultData(DiceUtils.roll(placeholder.getFormula()));
        settleResult(result);
        List<DiceRollResult> allResults = mergeResults(
                internalService.listResultEntities(summary.getId()), List.of(result));
        refreshSummary(summary, allResults);
        return new DiceRollProgressVO(
                DiceRollSummaryVO.from(summary),
                DiceRollDetailVO.from(result),
                List.of());
    }

    private KpDiceToolResult createAndSettle(
            Long conversationId, String reason, List<DiceRollResultCreateDTO> drafts) {
        DiceRollAggregate aggregate = internalService.createDiceRoll(
                conversationId, reason.trim(), drafts);
        settleAlreadyRolled(aggregate.results());
        List<DiceRollResult> allResults = mergeResults(
                internalService.listResultEntities(aggregate.summary().getId()),
                aggregate.results());
        refreshSummary(aggregate.summary(), allResults);
        return toolResult(aggregate.summary(), aggregate.results());
    }

    private void settleAlreadyRolled(List<DiceRollResult> results) {
        for (DiceRollResult result : safeResults(results)) {
            if (result.getResolvedAt() == null
                    && result.getResultData() != null
                    && result.getResultData().getResult() != null) {
                settleResult(result);
            }
        }
    }

    private void settleResult(DiceRollResult result) {
        DiceResolutionDataVO resolution = resolution(result);
        String type = resolution.getType();
        if (!DiceRollConstant.TYPE_CHECK.equals(type)
                && !DiceRollConstant.TYPE_OPPOSED_CHECK.equals(type)
                && !DiceRollConstant.TYPE_SAN_CHECK.equals(type)) {
            throw new UserRequestException("暂不支持该掷骰结算类型：" + type);
        }
        Map<String, Object> rule = resolution.getRule();
        CocDiceRules.CheckResolution check = CocDiceRules.resolveCheck(
                requireRoll(result),
                intValue(rule, "targetValue"),
                CocCheckDifficulty.valueOf(stringValue(rule, "difficulty")));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", stringValue(rule, "characterName"));
        outcome.put("checkName", stringValue(rule, "checkName"));
        outcome.put("category", check.outcome().name());
        resolution.setOutcome(outcome);
        LocalDateTime now = LocalDateTime.now();
        result.setResolvedAt(now).setUpdatedAt(now);
        internalService.saveResult(result);
    }

    private void refreshSummary(DiceRollSummary summary, List<DiceRollResult> results) {
        int currentRound = summary.getRoundCount();
        boolean pendingUserDice = safeResults(results).stream()
                .filter(result -> Objects.equals(currentRound, result.getRoundNo()))
                .anyMatch(this::isPendingUserDice);
        summary.setStatus(pendingUserDice
                        ? DiceRollConstant.STATUS_PENDING
                        : DiceRollConstant.STATUS_COMPLETED)
                .setTotalResult(summaryFormatter.rebuildTotalResult(results))
                .setUpdatedAt(LocalDateTime.now());
        internalService.saveSummary(summary);
    }

    private boolean isPendingUserDice(DiceRollResult result) {
        DiceRollResultVO data = result.getResultData();
        return result.getCharacterId() == null
                && result.getResolvedAt() == null
                && data != null
                && data.getResult() == null
                && data.getModules() != null
                && !data.getModules().isEmpty();
    }

    private KpDiceToolResult toolResult(
            DiceRollSummary summary, List<DiceRollResult> created) {
        List<DiceRollResult> safeCreated = safeResults(created);
        String semantic = availableSemantic(safeCreated);
        return new KpDiceToolResult(
                DiceRollSummaryVO.from(summary),
                safeCreated.stream().map(DiceRollDetailVO::from).toList(),
                semantic);
    }

    private String availableSemantic(List<DiceRollResult> results) {
        if (results.isEmpty()) {
            return null;
        }
        if (results.stream().anyMatch(result -> DiceRollConstant.TYPE_OPPOSED_CHECK.equals(
                resolution(result).getType()))
                && results.stream().anyMatch(result -> result.getResolvedAt() == null)) {
            return null;
        }
        List<DiceRollResult> resolved = results.stream()
                .filter(result -> result.getResolvedAt() != null)
                .toList();
        if (resolved.isEmpty()) {
            return null;
        }
        String text = summaryFormatter.formatRound(resolved);
        return text.isBlank() ? null : text;
    }

    private DiceRollResultCreateDTO checkDraft(
            CocDiceCharacterVO card,
            String checkName,
            int targetValue,
            CocCheckDifficulty difficulty,
            CocPercentileModifier modifier,
            boolean pushed,
            String type,
            String reason,
            int displayOrder,
            String tieWinnerCharacterName) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("cardId", card.cardId());
        rule.put("characterName", card.name());
        rule.put("checkName", checkName);
        rule.put("targetValue", targetValue);
        rule.put("difficulty", difficulty.name());
        rule.put("modifier", modifier.name());
        rule.put("pushed", pushed);
        if (DiceRollConstant.TYPE_OPPOSED_CHECK.equals(type)) {
            rule.put("tieWinnerCharacterName", tieWinnerCharacterName);
        }
        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        draft.setCharacterId(card.participantId());
        draft.setDisplayOrder(displayOrder);
        draft.setDisplayType(type);
        draft.setReason(reason.trim());
        draft.setFormula(modifier.formula());
        draft.setResolutionData(DiceResolutionDataVO.pending(type, null, rule));
        return draft;
    }

    private List<KpDiceRequestDTOs.CheckTarget> requireTargets(
            List<KpDiceRequestDTOs.CheckTarget> targets, boolean opposed) {
        if (targets == null || targets.isEmpty()
                || (opposed && targets.size() < 2)) {
            throw new UserRequestException(opposed
                    ? "对抗检定至少需要两个角色"
                    : "检定角色不能为空");
        }
        Set<String> names = new HashSet<>();
        for (KpDiceRequestDTOs.CheckTarget target : targets) {
            if (target == null || !StringUtils.hasText(target.characterName())
                    || !StringUtils.hasText(target.checkName())) {
                throw new UserRequestException("检定角色名和检定项不能为空");
            }
            if (!names.add(target.characterName().trim())) {
                throw new UserRequestException("同一次检定不能重复选择角色");
            }
        }
        return List.copyOf(targets);
    }

    private List<String> requireCharacterNames(List<String> characterNames) {
        if (characterNames == null || characterNames.isEmpty()) {
            throw new UserRequestException("角色名不能为空");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String name : characterNames) {
            if (!StringUtils.hasText(name) || !unique.add(name.trim())) {
                throw new UserRequestException("角色名不能为空或重复");
            }
        }
        return List.copyOf(unique);
    }

    private int requireCheckValue(CocDiceCharacterVO card, String checkName) {
        if (card == null || card.cardId() == null) {
            throw new UserRequestException("角色卡不存在");
        }
        Integer value = card.checkValues().get(checkName.trim());
        if (value == null || value < 1 || value > 100) {
            throw new UserRequestException(
                    "角色“" + card.name() + "”没有可用的“" + checkName.trim() + "”检定值");
        }
        return value;
    }

    private CocPercentileModifier normalizeModifier(CocPercentileModifier modifier) {
        return modifier == null ? CocPercentileModifier.NORMAL : modifier;
    }

    private void requireContext(Long conversationId, Long runId) {
        if (conversationId == null || runId == null) {
            throw new UserRequestException("群聊或跑团上下文不存在");
        }
        conversationService.requireActive(conversationId);
    }

    private void requireRequest(Object request, String reason) {
        if (request == null || !StringUtils.hasText(reason)) {
            throw new UserRequestException("掷骰请求或原因不能为空");
        }
    }

    private void requireConversation(DiceRollSummary summary, Long conversationId) {
        if (!conversationId.equals(summary.getConversationId())) {
            throw new UserRequestException("前一次掷骰不属于当前群聊");
        }
    }

    private List<DiceRollResult> mergeResults(
            List<DiceRollResult> persisted, List<DiceRollResult> additional) {
        Map<Object, DiceRollResult> merged = new LinkedHashMap<>();
        for (DiceRollResult result : safeResults(persisted)) {
            merged.put(resultKey(result), result);
        }
        for (DiceRollResult result : safeResults(additional)) {
            merged.put(resultKey(result), result);
        }
        return merged.values().stream()
                .sorted(Comparator
                        .comparing(DiceRollResult::getRoundNo,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DiceRollResult::getDisplayOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DiceRollResult::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private Object resultKey(DiceRollResult result) {
        return result.getId() == null ? result : result.getId();
    }

    private List<DiceRollResult> safeResults(List<DiceRollResult> results) {
        return results == null ? List.of() : results;
    }

    private DiceResolutionDataVO resolution(DiceRollResult result) {
        if (result == null || result.getResolutionData() == null
                || result.getResolutionData().getRule() == null) {
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

    private String ruleString(DiceRollResult result, String key) {
        return stringValue(resolution(result).getRule(), key);
    }

    private String outcomeString(DiceRollResult result, String key) {
        Map<String, Object> outcome = resolution(result).getOutcome();
        return outcome == null ? null : Objects.toString(outcome.get(key), null);
    }

    private String stringValue(Map<String, Object> values, String key) {
        String value = values == null ? null : Objects.toString(values.get(key), null);
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException("掷骰规则字段无效：" + key);
        }
        return value;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
