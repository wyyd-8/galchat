package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.DamageSourceMode;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.HealingSourceMode;
import com.me.galchat.constant.HealingMode;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceRollAggregate;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.service.DiceFollowUpLocator;
import com.me.galchat.service.DiceMessageRoundAppender;
import com.me.galchat.service.DiceRandomSource;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocDiceOrchestrationServiceTest {

    private IDiceRollInternalService internal;
    private ICharacterCardService cards;
    private GroupConversationService conversations;
    private DiceFollowUpLocator followUps;
    private DiceRandomSource randomSource;
    private DiceMessageRoundAppender messageRoundAppender;
    private TrpgCombatLifecycleService combatLifecycleService;
    private CocDiceOrchestrationService service;

    @BeforeEach
    void setUp() {
        internal = mock(IDiceRollInternalService.class);
        cards = mock(ICharacterCardService.class);
        conversations = mock(GroupConversationService.class);
        followUps = mock(DiceFollowUpLocator.class);
        randomSource = mock(DiceRandomSource.class);
        messageRoundAppender = mock(DiceMessageRoundAppender.class);
        combatLifecycleService = mock(TrpgCombatLifecycleService.class);
        service = new CocDiceOrchestrationService(
                internal,
                cards,
                conversations,
                followUps,
                new CocDiceSummaryFormatter(),
                randomSource,
                messageRoundAppender,
                combatLifecycleService);
        when(conversations.requireActive(7L))
                .thenReturn(new GroupConversation().setId(7L).setStatus("active"));
    }

    @Test
    void agentResultIsVisibleWhilePlayerResultIsPending() {
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
        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(result.semanticResult()).isEqualTo("陈默成功");
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
    void retryDoesNotRerollOrReapplySanLoss() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED)
                .setTotalResult("林恩理智-6");
        DiceRollResult resolved = sanLoss(
                201L,
                101L,
                "林恩",
                11L,
                null,
                new DiceRollResultVO("1D6", List.of(), 6),
                6)
                .setRoundNo(1)
                .setResolvedAt(LocalDateTime.now());
        when(internal.requireResult(201L)).thenReturn(resolved);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);

        var result = service.rollPlayerResult(201L);

        assertThat(result.rolledResult().getId()).isEqualTo(201L);
        assertThat(result.summary().getTotalResult()).isEqualTo("林恩理智-6");
        verify(internal, never()).saveResult(any());
        verify(internal, never()).saveSummary(any());
        verify(cards, never()).lockDiceCharacter(any(), any());
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
    void completedRoundOnlyIsIncludedInTotalResult() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult completed = resolvedCheck(
                200L, 101L, 1, "陈默", 12L, 45, "SUCCESS")
                .setCharacterId(88L);
        DiceRollResult rolling = resolvedCheck(
                201L, 101L, 2, "林恩", 11L, 70, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D1"))
                .setResolvedAt(null);
        rolling.getResolutionData().setOutcome(null);
        DiceRollResult stillPending = resolvedCheck(
                202L, 101L, 2, "周晴", 13L, 60, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D100"))
                .setResolvedAt(null);
        stillPending.getResolutionData().setOutcome(null);
        when(internal.requireResult(201L)).thenReturn(rolling);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenReturn(List.of(completed, rolling, stillPending));

        var progress = service.rollPlayerResult(201L);

        assertThat(progress.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(progress.summary().getTotalResult()).isEqualTo("陈默成功");
        verify(internal).saveSummary(summary);
        verify(internal, never()).appendDiceRollRound(any(), any(), any());
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
        verify(internal, never()).saveResult(any());
        verify(cards, never()).updateDiceCharacter(any());
    }

    @Test
    void oldRoundPendingResultCannotBeRolledAfterRoundAdvance() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult stale = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D100"))
                .setResolvedAt(null);
        stale.getResolutionData().setOutcome(null);
        when(internal.requireResult(201L)).thenReturn(stale);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);

        assertThatThrownBy(() -> service.rollPlayerResult(201L))
                .hasMessageContaining("当前掷骰轮次");

        verify(internal, never()).saveResult(any());
        verify(internal, never()).saveSummary(any());
        verify(cards, never()).updateDiceCharacter(any());
    }

    @Test
    void sanLossUsesActualBranchAndDefersMadnessWhilePlayerDiceIsPending() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestSanCheck")))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult playerCheck = resolvedSanCheck(
                201L, 101L, "林恩", 11L, null, "FAILURE");
        DiceRollResult agentCheck = resolvedSanCheck(
                202L, 101L, "陈默", 12L, 88L, "FAILURE");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenReturn(List.of(playerCheck, agentCheck));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> created = materialize(101L, 2, drafts);
                    created.get(1).setResultData(
                            new DiceRollResultVO("1D6", List.of(), 6));
                    return created;
                });
        CocCharacter agentCard = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setSanCurrent(60);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(agentCard);

        KpDiceToolResult created = service.rollSanLoss(
                7L,
                5L,
                new KpDiceRequestDTOs.SanLoss("目睹怪物", "0", "1D6"));

        assertThat(created.summary().getRoundCount()).isEqualTo(2);
        assertThat(created.results())
                .filteredOn(result -> result.getCharacterId() == null)
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getResultData().getFormula()).isEqualTo("1D6");
                    assertThat(result.getResultData().getResult()).isNull();
                });
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(101L), any());
    }

    @Test
    void sanConstantZeroDoesNotCreateClickablePlaceholder() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestSanCheck")))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult playerCheck = resolvedSanCheck(
                201L, 101L, "林恩", 11L, null, "SUCCESS");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(playerCheck));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> created = materialize(101L, 2, drafts);
                    created.getFirst().setResultData(
                            new DiceRollResultVO("0", List.of(), 0));
                    return created;
                });
        CocCharacter card = new CocCharacter()
                .setId(11L)
                .setRunId(5L)
                .setName("林恩")
                .setSanCurrent(60);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);

        KpDiceToolResult result = service.rollSanLoss(
                7L,
                5L,
                new KpDiceRequestDTOs.SanLoss("看到尸体", "0", "1D6"));

        assertThat(result.results()).singleElement().satisfies(detail -> {
            assertThat(detail.getResultData().getFormula()).isEqualTo("0");
            assertThat(detail.getResultData().getModules()).isEmpty();
            assertThat(detail.getResultData().getResult()).isZero();
        });
        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(result.semanticResult()).isEqualTo("林恩理智-0");
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(101L), any());
    }

    @Test
    void madnessRoundIsCreatedOnceUnderSummaryLock() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult playerLoss = sanLoss(
                301L, 101L, "林恩", 11L, null, DiceUtils.prepare("1D1*6"), null);
        DiceRollResult agentLoss = sanLoss(
                302L,
                101L,
                "陈默",
                12L,
                88L,
                new DiceRollResultVO("1D6", List.of(), 5),
                5);
        when(internal.requireResult(301L)).thenReturn(playerLoss);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenReturn(List.of(playerLoss, agentLoss));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> created = materialize(101L, 3, drafts);
                    for (DiceRollResult result : created) {
                        if (result.getCharacterId() != null) {
                            result.setResultData(new DiceRollResultVO("1D10", List.of(), 4));
                        }
                    }
                    return created;
                });
        CocCharacter playerCard = new CocCharacter()
                .setId(11L)
                .setRunId(5L)
                .setName("林恩")
                .setSanCurrent(60);
        CocCharacter agentCard = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setSanCurrent(55);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(playerCard);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(agentCard);

        var progress = service.rollPlayerResult(301L);
        var retry = service.rollPlayerResult(301L);

        assertThat(progress.createdResults()).hasSize(4);
        assertThat(progress.createdResults())
                .extracting(com.me.galchat.domain.vo.DiceRollDetailVO::getRoundNo)
                .containsOnly(3);
        assertThat(retry.createdResults()).isEmpty();
        verify(internal, times(2)).requireSummaryForUpdate(101L);
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(101L), any());
        verify(messageRoundAppender).appendRounds(7L, 101L, List.of(3));
    }

    @Test
    void agentOnlySanLossCreatesAndSettlesMadnessRoundImmediately() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestSanCheck")))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult agentCheck = resolvedSanCheck(
                201L, 101L, "陈默", 12L, 88L, "FAILURE");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(agentCheck));
        AtomicLong appendCount = new AtomicLong();
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    int round = appendCount.incrementAndGet() == 1 ? 2 : 3;
                    List<DiceRollResult> created = materialize(101L, round, drafts);
                    for (DiceRollResult result : created) {
                        String type = result.getResolutionData().getType();
                        int value = switch (type) {
                            case "SAN_LOSS" -> 6;
                            case "TEMPORARY_INSANITY_TYPE" -> 9;
                            default -> 4;
                        };
                        result.setResultData(new DiceRollResultVO(
                                result.getResultData().getFormula(), List.of(), value));
                    }
                    return created;
                });
        when(randomSource.d100()).thenReturn(37);
        CocCharacter card = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setSanCurrent(60);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(card);

        KpDiceToolResult result = service.rollSanLoss(
                7L,
                5L,
                new KpDiceRequestDTOs.SanLoss("目睹怪物", "0", "1D6"));

        assertThat(result.summary().getRoundCount()).isEqualTo(3);
        assertThat(result.results()).hasSize(3);
        assertThat(result.semanticResult())
                .contains("陈默理智-6；进入临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫），持续4小时");
        assertThat(card.getSanCurrent()).isEqualTo(54);
        assertThat(card.getTemporaryInsanityPhase()).isEqualTo("9:037");
        verify(internal, times(2))
                .appendDiceRollRound(eq(7L), eq(101L), any());
    }

    @Test
    void typeNineStoresRandomCatalogNumberWithoutCreatingD100Result() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(3)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult type = insanityResult(
                401L,
                "TEMPORARY_INSANITY_TYPE",
                new DiceRollResultVO("1D10", List.of(), 9),
                true);
        type.getResolutionData().setOutcome(Map.of(
                "characterName", "林恩", "typeRoll", 9));
        DiceRollResult duration = insanityResult(
                402L,
                "TEMPORARY_INSANITY_DURATION",
                DiceUtils.prepare("1D1*4"),
                false);
        when(internal.requireResult(402L)).thenReturn(duration);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(type, duration));
        when(randomSource.d100()).thenReturn(37);
        CocCharacter card = new CocCharacter()
                .setId(11L)
                .setRunId(5L)
                .setName("林恩")
                .setSanCurrent(54);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);

        service.rollPlayerResult(402L);

        assertThat(List.of(type, duration))
                .noneMatch(result -> "1D100".equals(result.getResultData().getFormula()));
        assertThat(card.getTemporaryInsanityPhase()).isEqualTo("9:037");
        assertThat(card.getTemporaryInsanityRemainingHours()).isEqualTo(4);
        verify(randomSource).d100();
        verify(messageRoundAppender, never())
                .appendRounds(any(), any(), any());
    }

    @Test
    void standaloneDamageCreatesFirstRoundAndAppliesHp() {
        CocDiceCharacterVO targetCard = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            DiceRollResultCreateDTO draft = drafts.getFirst();
            DiceRollResult result = new DiceRollResult()
                    .setId(501L)
                    .setSummaryId(111L)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(1)
                    .setDisplayOrder(1)
                    .setResultData(new DiceRollResultVO("3", List.of(), 3))
                    .setResolutionData(draft.getResolutionData());
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    List.of(result));
        });
        CocCharacter locked = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setHpCurrent(10)
                .setHpMax(10)
                .setCon(50)
                .setMajorWound(false)
                .setUnconscious(false)
                .setDead(false);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "坠入坑中",
                        DamageSourceMode.STANDALONE,
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "陈默", null, "3"))));

        assertThat(result.summary().getRoundCount()).isEqualTo(1);
        assertThat(locked.getHpCurrent()).isEqualTo(7);
        assertThat(result.semanticResult()).contains("陈默生命-3");
    }

    @Test
    void majorWoundCharacterReachingZeroStartsDying() {
        CocDiceCharacterVO targetCard = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            DiceRollResultCreateDTO draft = drafts.getFirst();
            DiceRollResult result = new DiceRollResult()
                    .setId(501L)
                    .setSummaryId(111L)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(1)
                    .setDisplayOrder(1)
                    .setResultData(new DiceRollResultVO("4", List.of(), 4))
                    .setResolutionData(draft.getResolutionData());
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    List.of(result));
        });
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(4)
                .setMajorWound(true)
                .setDying(false);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "持续失血",
                        DamageSourceMode.STANDALONE,
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "陈默", null, "4"))));

        assertThat(locked.getHpCurrent()).isZero();
        assertThat(locked.getUnconscious()).isTrue();
        assertThat(locked.getDying()).isTrue();
        verify(combatLifecycleService)
                .forfeitCurrentRoundSlot(7L, 12L);
    }

    @Test
    void standaloneHealingClampsAppliedHpAtMaximum() {
        CocDiceCharacterVO targetCard = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            DiceRollResultCreateDTO draft = drafts.getFirst();
            DiceRollResult result = new DiceRollResult()
                    .setId(501L)
                    .setSummaryId(111L)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(1)
                    .setDisplayOrder(1)
                    .setResultData(new DiceRollResultVO("4", List.of(), 4))
                    .setResolutionData(draft.getResolutionData());
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    List.of(result));
        });
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(8);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "包扎伤口",
                        HealingSourceMode.STANDALONE,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "4"))));

        assertThat(locked.getHpCurrent()).isEqualTo(10);
        assertThat(result.semanticResult()).isEqualTo("陈默生命+2");
        assertThat(result.results()).singleElement().satisfies(healing ->
                assertThat(healing.getResolution().getEffect())
                        .containsEntry("hpBefore", 8)
                        .containsEntry("hpAfter", 10)
                        .containsEntry("hpGain", 2));
    }

    @Test
    void healingAboveZeroStopsDyingButKeepsUnconsciousAndMajorWound() {
        CocDiceCharacterVO targetCard = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        stubCreate(7L);
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(0)
                .setMajorWound(true)
                .setUnconscious(true)
                .setDying(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救止血",
                        HealingSourceMode.STANDALONE,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1"))));

        assertThat(locked.getHpCurrent()).isEqualTo(10);
        assertThat(locked.getDying()).isFalse();
        assertThat(locked.getUnconscious()).isTrue();
        assertThat(locked.getMajorWound()).isTrue();
    }

    @Test
    void firstAidHealingClearsInjuryStatuses() {
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, 88L, "陈默", 45));
        stubCreate(7L);
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(5)
                .setMajorWound(true)
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救处理",
                        HealingSourceMode.STANDALONE,
                        HealingMode.FIRST_AID,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1"))));

        assertThat(locked.getMajorWound()).isFalse();
        assertThat(locked.getUnconscious()).isFalse();
        assertThat(result.semanticResult())
                .isEqualTo("陈默生命+5；解除重伤；脱离昏迷");
        assertThat(result.results()).singleElement().satisfies(healing ->
                assertThat(healing.getResolution().getEffect())
                        .containsEntry("majorWoundBefore", true)
                        .containsEntry("majorWound", false)
                        .containsEntry("majorWoundChanged", true)
                        .containsEntry("unconsciousBefore", true)
                        .containsEntry("unconscious", false)
                        .containsEntry("unconsciousChanged", true));
    }

    @Test
    void medicineHealingClearsOnlyMajorWound() {
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, 88L, "陈默", 45));
        stubCreate(7L);
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(5)
                .setMajorWound(true)
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "医学治疗",
                        HealingSourceMode.STANDALONE,
                        HealingMode.MEDICINE,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1"))));

        assertThat(locked.getMajorWound()).isFalse();
        assertThat(locked.getUnconscious()).isTrue();
        assertThat(result.semanticResult())
                .isEqualTo("陈默生命+5；解除重伤");
    }

    @Test
    void healingRequiresARecoveryMode() {
        assertThatThrownBy(() -> service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "来源不明的治疗",
                        HealingSourceMode.STANDALONE,
                        null,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1")))))
                .hasMessageContaining("恢复方式不能为空");

        verify(cards, never()).requireDiceCharacter(any(), any());
        verify(internal, never()).createDiceRoll(any(), any(), any());
    }

    @Test
    void followUpHealingAppendsToSuccessfulSingleCheck() {
        when(followUps.requireLatestSummaryId(
                7L,
                Set.of(
                        DiceRollConstant.TOOL_REQUEST_CHECK,
                        DiceRollConstant.TOOL_REQUEST_PUSHED_CHECK)))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult source = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "SUCCESS");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(source));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> results = materialize(101L, 2, drafts);
                    results.getFirst().setResultData(
                            new DiceRollResultVO("3", List.of(), 3));
                    return results;
                });
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, 88L, "陈默", 45));
        CocCharacter locked = damageCard(12L, "陈默").setHpCurrent(4);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救伤口",
                        HealingSourceMode.FOLLOW_UP,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", "林恩", "3"))));

        assertThat(result.summary().getId()).isEqualTo(101L);
        assertThat(result.summary().getRoundCount()).isEqualTo(2);
        assertThat(locked.getHpCurrent()).isEqualTo(7);
        assertThat(result.results()).singleElement().satisfies(healing -> {
            assertThat(healing.getRoundNo()).isEqualTo(2);
            assertThat(healing.getResolution().getSourceResultId())
                    .isEqualTo(201L);
        });
    }

    @Test
    void followUpHealingRejectsFailedSingleCheck() {
        when(followUps.requireLatestSummaryId(eq(7L), any()))
                .thenReturn(101L);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(
                new DiceRollSummary()
                        .setId(101L)
                        .setConversationId(7L)
                        .setRoundCount(1)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));
        when(internal.listResultEntities(101L)).thenReturn(List.of(
                resolvedCheck(201L, 101L, 1, "林恩", 11L, 70, "FAILURE")));

        assertThatThrownBy(() -> service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救伤口",
                        HealingSourceMode.FOLLOW_UP,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", "林恩", "1")))))
                .hasMessageContaining("前置检定未成功");

        verify(internal, never()).appendDiceRollRound(any(), any(), any());
        verify(cards, never()).requireDiceCharacter(any(), any());
    }

    @Test
    void followUpDamageRequiresAWinningOrSuccessfulSource() {
        when(followUps.requireLatestSummaryId(
                eq(7L), any())).thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(
                resolvedCheck(201L, 101L, 1, "林恩", 11L, 70, "FAILURE")));

        assertThatThrownBy(() -> service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "攻击邪教徒",
                        DamageSourceMode.FOLLOW_UP,
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "邪教徒", "林恩", "1D6")))))
                .hasMessageContaining("前置检定未成功");
    }

    @Test
    void followUpLookupCannotCrossConversation() {
        when(followUps.requireLatestSummaryId(eq(7L), any()))
                .thenReturn(101L);
        when(internal.requireSummaryForUpdate(101L))
                .thenReturn(new DiceRollSummary()
                        .setId(101L)
                        .setConversationId(8L)
                        .setRoundCount(1)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));

        assertThatThrownBy(() -> service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "攻击邪教徒",
                        DamageSourceMode.FOLLOW_UP,
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "邪教徒", "林恩", "1D6")))))
                .hasMessageContaining("不属于当前群聊");

        verify(internal, never()).listResultEntities(any());
        verify(internal, never()).appendDiceRollRound(any(), any(), any());
        verify(cards, never()).requireDiceCharacter(any(), any());
        verify(cards, never()).updateDiceCharacter(any());
    }

    @Test
    void majorWoundConRoundIsCreatedOnceUnderSummaryLock() {
        CocDiceCharacterVO playerTarget = card(11L, null, "林恩", 70);
        CocDiceCharacterVO agentTarget = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(playerTarget);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(agentTarget);
        AtomicReference<List<DiceRollResult>> damageRows = new AtomicReference<>();
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            List<DiceRollResult> rows = materialize(111L, 1, drafts);
            rows.get(0).setId(501L).setResultData(DiceUtils.prepare("1D1*6"));
            rows.get(1)
                    .setId(502L)
                    .setResultData(new DiceRollResultVO("6", List.of(), 6));
            damageRows.set(rows);
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_PENDING),
                    rows);
        });
        CocCharacter playerCard = damageCard(11L, "林恩");
        CocCharacter agentCard = damageCard(12L, "陈默");
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(playerCard);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(agentCard);

        KpDiceToolResult created = service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "爆炸冲击",
                        DamageSourceMode.STANDALONE,
                        List.of(
                                new KpDiceRequestDTOs.DamageTarget(
                                        "林恩", null, "1D1*6"),
                                new KpDiceRequestDTOs.DamageTarget(
                                        "陈默", null, "6"))));

        assertThat(created.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(created.summary().getRoundCount()).isEqualTo(1);
        verify(internal, never()).appendDiceRollRound(eq(7L), eq(111L), any());

        DiceRollSummary summary = new DiceRollSummary()
                .setId(111L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        when(internal.requireResult(501L)).thenAnswer(
                ignored -> damageRows.get().getFirst());
        when(internal.requireSummaryForUpdate(111L)).thenReturn(summary);
        when(internal.listResultEntities(111L)).thenAnswer(
                ignored -> damageRows.get());
        when(internal.appendDiceRollRound(eq(7L), eq(111L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> conRows = materialize(111L, 2, drafts);
                    for (DiceRollResult con : conRows) {
                        if (con.getCharacterId() != null) {
                            con.setResultData(
                                    new DiceRollResultVO("1D100", List.of(), 35));
                        }
                    }
                    return conRows;
                });

        var progress = service.rollPlayerResult(501L);
        var retry = service.rollPlayerResult(501L);

        assertThat(progress.createdResults()).hasSize(2);
        assertThat(progress.createdResults())
                .extracting(result -> result.getResolution().getType())
                .containsOnly("MAJOR_WOUND_CON");
        assertThat(progress.createdResults())
                .extracting(com.me.galchat.domain.vo.DiceRollDetailVO::getRoundNo)
                .containsOnly(2);
        assertThat(retry.createdResults()).isEmpty();
        verify(internal, times(2)).requireSummaryForUpdate(111L);
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(111L), any());
        verify(messageRoundAppender).appendRounds(7L, 111L, List.of(2));
    }

    @Test
    void failedMajorWoundConMakesCharacterUnconscious() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(111L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                "MAJOR_WOUND_CON",
                501L,
                Map.of(
                        "runId", 5L,
                        "cardId", 11L,
                        "characterName", "林恩",
                        "targetValue", 50,
                        "hpLoss", 6));
        DiceRollResult pending = new DiceRollResult()
                .setId(601L)
                .setSummaryId(111L)
                .setCharacterId(null)
                .setRoundNo(2)
                .setDisplayOrder(1)
                .setResultData(DiceUtils.prepare("1D1*100"))
                .setResolutionData(resolution);
        when(internal.requireResult(601L)).thenReturn(pending);
        when(internal.requireSummaryForUpdate(111L)).thenReturn(summary);
        when(internal.listResultEntities(111L)).thenReturn(List.of(pending));
        CocCharacter card = damageCard(11L, "林恩")
                .setHpCurrent(4)
                .setMajorWound(true);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);

        var progress = service.rollPlayerResult(601L);

        assertThat(progress.rolledResult().getResolution().getOutcome())
                .containsEntry("category", "FUMBLE");
        assertThat(card.getUnconscious()).isTrue();
        assertThat(progress.summary().getTotalResult())
                .isEqualTo("林恩生命-6；受到重伤；CON检定失败，陷入昏迷");
    }

    @Test
    void unconsciousPlayerRecoveryCreatesPendingConDie() {
        CocCharacter card = damageCard(11L, "林恩")
                .setActorType("PLAYER")
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);
        stubCreate(7L);

        KpDiceToolResult result = service.requestUnconsciousRecovery(
                7L, 5L, 11L);

        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(result.results()).singleElement().satisfies(die -> {
            assertThat(die.getResolution().getType())
                    .isEqualTo("UNCONSCIOUS_RECOVERY_CON");
            assertThat(die.getResultData().getResult()).isNull();
        });
        assertThat(card.getUnconscious()).isTrue();
    }

    @Test
    void unconsciousAgentRecoverySettlesAndWakesWithoutAnotherAgentTurn() {
        CocCharacter card = damageCard(12L, "陈默")
                .setActorType("BOT")
                .setParticipantId(88L)
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(card);
        stubCreate(7L);

        KpDiceToolResult result = service.requestUnconsciousRecovery(
                7L, 5L, 12L);

        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(result.semanticResult()).isEqualTo("陈默CON检定成功，脱离昏迷");
        assertThat(card.getUnconscious()).isFalse();
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

    private DiceRollResult resolvedSanCheck(
            Long id,
            Long summaryId,
            String name,
            Long cardId,
            Long characterId,
            String category) {
        DiceRollResult result = resolvedCheck(
                id, summaryId, 1, name, cardId, 60, category);
        result.setCharacterId(characterId);
        result.getResolutionData().setType("SAN_CHECK");
        result.getResolutionData().getRule().put("checkName", "理智");
        return result;
    }

    private DiceRollResult sanLoss(
            Long id,
            Long summaryId,
            String name,
            Long cardId,
            Long characterId,
            DiceRollResultVO resultData,
            Integer settledLoss) {
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                "SAN_LOSS",
                id - 100,
                Map.of(
                        "runId", 5L,
                        "cardId", cardId,
                        "characterName", name,
                        "sanCheckOutcome", "FAILURE"));
        DiceRollResult result = new DiceRollResult()
                .setId(id)
                .setSummaryId(summaryId)
                .setCharacterId(characterId)
                .setRoundNo(2)
                .setDisplayOrder(id.intValue() - 300)
                .setResultData(resultData)
                .setResolutionData(resolution);
        if (settledLoss != null) {
            resolution
                    .setOutcome(Map.of("characterName", name, "sanLoss", settledLoss))
                    .setEffect(Map.of(
                            "sanBefore", 60,
                            "sanAfter", 60 - settledLoss,
                            "sanLoss", settledLoss));
            result.setResolvedAt(LocalDateTime.now());
        }
        return result;
    }

    private DiceRollResult insanityResult(
            Long id, String type, DiceRollResultVO resultData, boolean resolved) {
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                type,
                301L,
                Map.of(
                        "runId", 5L,
                        "cardId", 11L,
                        "characterName", "林恩",
                        "sanLoss", 6));
        return new DiceRollResult()
                .setId(id)
                .setSummaryId(101L)
                .setCharacterId(null)
                .setRoundNo(3)
                .setDisplayOrder(id.intValue() - 400)
                .setResultData(resultData)
                .setResolutionData(resolution)
                .setResolvedAt(resolved ? LocalDateTime.now() : null);
    }

    private CocCharacter damageCard(Long id, String name) {
        return new CocCharacter()
                .setId(id)
                .setRunId(5L)
                .setName(name)
                .setHpCurrent(10)
                .setHpMax(10)
                .setCon(50)
                .setMajorWound(false)
                .setUnconscious(false)
                .setDead(false);
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
