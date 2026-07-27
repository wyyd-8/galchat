package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceRollAggregate;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.service.DiceFollowUpLocator;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.utils.DiceUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocDiceOrchestrationServiceTest {

    private IDiceRollInternalService internal;
    private ICharacterCardService cards;
    private GroupConversationService conversations;
    private DiceFollowUpLocator followUps;
    private CocDiceOrchestrationService service;

    @BeforeEach
    void setUp() {
        internal = mock(IDiceRollInternalService.class);
        cards = mock(ICharacterCardService.class);
        conversations = mock(GroupConversationService.class);
        followUps = mock(DiceFollowUpLocator.class);
        service = new CocDiceOrchestrationService(
                internal, cards, conversations, followUps, new CocDiceSummaryFormatter());
        when(conversations.requireActive(7L))
                .thenReturn(new GroupConversation().setId(7L).setStatus("active"));
    }

    @Test
    void groupCheckCreatesPlayerPlaceholderAndResolvesAgentSemantics() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(agent(12L, "陈默", 45));
        stubCreate(7L);

        KpDiceToolResult result = service.requestCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Check(
                        "调查书房",
                        CocCheckDifficulty.REGULAR,
                        List.of(
                                target("林恩", "侦查"),
                                target("陈默", "侦查"))));

        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).getResultData().getResult()).isNull();
        assertThat(result.results().get(0).getResultData().getModules()).isNotEmpty();
        assertThat(result.results().get(1).getResolution().getOutcome())
                .containsKey("category");
        assertThat(result.results().get(1).getResolution().getOutcome())
                .doesNotContainKey("rank");
    }

    @Test
    void checkRuleSnapshotUsesCardValueAndStableKeys() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));
        stubCreate(7L);

        service.requestCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Check(
                        "调查书房",
                        CocCheckDifficulty.HARD,
                        List.of(new KpDiceRequestDTOs.CheckTarget(
                                "林恩", "侦查", CocPercentileModifier.BONUS_1))));

        @SuppressWarnings("unchecked")
        List<DiceRollResultCreateDTO> drafts =
                (List<DiceRollResultCreateDTO>) org.mockito.Mockito.mockingDetails(internal)
                        .getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("createDiceRoll"))
                        .findFirst()
                        .orElseThrow()
                        .getArgument(2);
        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.getFormula()).isEqualTo("1D100#");
            assertThat(draft.getResolutionData().getRule())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "cardId", 11L,
                            "characterName", "林恩",
                            "checkName", "侦查",
                            "targetValue", 70,
                            "difficulty", "HARD",
                            "modifier", "BONUS_1",
                            "pushed", false));
        });
    }

    @Test
    void opposedResultReturnsWinnerInsteadOfRawRanks() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(agent(12L, "陈默", 45));
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            List<DiceRollResult> materialized = materialize(101L, 1, drafts);
            materialized.get(0)
                    .setCharacterId(99L)
                    .setResultData(new DiceRollResultVO("1D100", List.of(), 35));
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(101L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    materialized);
        });

        KpDiceToolResult result = service.requestOpposedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Opposed(
                        "争夺手枪",
                        List.of(
                                target("林恩", "侦查"),
                                target("陈默", "侦查")),
                        null));

        assertThat(result.semanticResult()).isEqualTo("林恩获胜");
        assertThat(result.semanticResult()).doesNotContain("困难", "极难");
    }

    @Test
    void pushedCheckUsesLatestCompatibleFailureSnapshot() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestCheck")))
                .thenReturn(101L);
        DiceRollSummary previous = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult failed = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "FAILURE");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(previous);
        when(internal.listResultEntities(101L)).thenReturn(List.of(failed));
        when(internal.appendDiceRollRound(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    return materialize(101L, 2, drafts);
                });

        KpDiceToolResult result = service.requestPushedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Pushed("孤注一掷搜索密室", List.of("林恩")));

        assertThat(result.results()).singleElement().satisfies(pushed -> {
            assertThat(pushed.getRoundNo()).isEqualTo(2);
            assertThat(pushed.getResolution().getOutcome()).isNull();
        });
        verify(followUps).requireLatestSummaryId(7L, Set.of("requestCheck"));
    }

    @Test
    void pushedCheckRejectsPreviousSuccess() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestCheck")))
                .thenReturn(101L);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED));
        when(internal.listResultEntities(101L)).thenReturn(List.of(
                resolvedCheck(201L, 101L, 1, "林恩", 11L, 70, "SUCCESS")));

        assertThatThrownBy(() -> service.requestPushedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Pushed("不合法的孤注一掷", List.of("林恩"))))
                .hasMessageContaining("失败");
    }

    @Test
    void repeatedPlayerRollReturnsSavedResultWithoutRollingOrApplyingAgain() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED)
                .setTotalResult("林恩成功");
        DiceRollResult resolved = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "SUCCESS")
                .setCharacterId(null)
                .setResolvedAt(LocalDateTime.now());
        when(internal.requireResult(201L)).thenReturn(resolved);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);

        var result = service.rollPlayerResult(201L);

        assertThat(result.rolledResult().getId()).isEqualTo(201L);
        assertThat(result.summary().getTotalResult()).isEqualTo("林恩成功");
        verify(internal, never()).saveResult(any());
        verify(cards, never()).updateDiceCharacter(any());
    }

    @Test
    void playerRollResolvesStoredRuleAndRebuildsSummary() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult pending = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D1"))
                .setResolvedAt(null);
        pending.getResolutionData().setOutcome(null);
        when(internal.requireResult(201L)).thenReturn(pending);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(pending));

        var result = service.rollPlayerResult(201L);

        assertThat(result.rolledResult().getResultData().getResult()).isEqualTo(1);
        assertThat(result.rolledResult().getResolution().getOutcome())
                .containsEntry("category", "CRITICAL_SUCCESS");
        assertThat(result.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(result.summary().getTotalResult()).isEqualTo("林恩大成功");
        verify(internal).saveResult(pending);
        verify(internal).saveSummary(summary);
    }

    @Test
    void playerCannotUseRollEndpointForAgentResult() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult agentResult = resolvedCheck(
                201L, 101L, 1, "陈默", 12L, 45, "SUCCESS")
                .setCharacterId(88L);
        when(internal.requireResult(201L)).thenReturn(agentResult);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);

        assertThatThrownBy(() -> service.rollPlayerResult(201L))
                .hasMessageContaining("不是玩家");
    }

    private void stubCreate(Long conversationId) {
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            String reason = invocation.getArgument(1);
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            List<DiceRollResult> results = materialize(101L, 1, drafts);
            boolean pending = results.stream().anyMatch(result ->
                    result.getCharacterId() == null
                            && result.getResultData().getResult() == null);
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(101L)
                            .setConversationId(conversationId)
                            .setReason(reason)
                            .setRoundCount(1)
                            .setStatus(pending
                                    ? DiceRollConstant.STATUS_PENDING
                                    : DiceRollConstant.STATUS_COMPLETED),
                    results);
        });
    }

    private List<DiceRollResult> materialize(
            Long summaryId, int roundNo, List<DiceRollResultCreateDTO> drafts) {
        AtomicLong ids = new AtomicLong(201L);
        List<DiceRollResult> results = new ArrayList<>();
        for (DiceRollResultCreateDTO draft : drafts) {
            boolean player = draft.getCharacterId() == null;
            DiceRollResultVO resultData = player
                    ? DiceUtils.prepare(draft.getFormula())
                    : new DiceRollResultVO(draft.getFormula(), List.of(), 35);
            results.add(new DiceRollResult()
                    .setId(ids.getAndIncrement())
                    .setSummaryId(summaryId)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(roundNo)
                    .setDisplayOrder(draft.getDisplayOrder())
                    .setReason(draft.getReason())
                    .setResultData(resultData)
                    .setResolutionData(draft.getResolutionData()));
        }
        return results;
    }

    private DiceRollResult resolvedCheck(
            Long id,
            Long summaryId,
            int roundNo,
            String name,
            Long cardId,
            int target,
            String category) {
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                "CHECK",
                null,
                Map.of(
                        "cardId", cardId,
                        "characterName", name,
                        "checkName", "侦查",
                        "targetValue", target,
                        "difficulty", "REGULAR",
                        "modifier", "NORMAL",
                        "pushed", false))
                .setOutcome(Map.of(
                        "characterName", name,
                        "checkName", "侦查",
                        "category", category));
        return new DiceRollResult()
                .setId(id)
                .setSummaryId(summaryId)
                .setRoundNo(roundNo)
                .setDisplayOrder(1)
                .setResultData(new DiceRollResultVO("1D100", List.of(), 75))
                .setResolutionData(resolution)
                .setResolvedAt(LocalDateTime.now());
    }

    private KpDiceRequestDTOs.CheckTarget target(String characterName, String checkName) {
        return new KpDiceRequestDTOs.CheckTarget(
                characterName, checkName, CocPercentileModifier.NORMAL);
    }

    private CocDiceCharacterVO player(String name, int checkValue) {
        return card(11L, null, name, checkValue);
    }

    private CocDiceCharacterVO agent(Long participantId, String name, int checkValue) {
        return card(12L, participantId, name, checkValue);
    }

    private CocDiceCharacterVO card(
            Long cardId, Long participantId, String name, int checkValue) {
        return new CocDiceCharacterVO(
                cardId,
                participantId,
                name,
                Map.of("侦查", checkValue),
                10,
                10,
                60,
                60,
                50,
                0,
                false,
                false,
                false,
                false,
                false,
                null,
                null);
    }
}
