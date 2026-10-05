package com.me.galchat.service.impl.group;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupTurnCheckpoint;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.exception.TurnCheckpointUnavailableException;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupTurnCheckpointMapper;
import com.me.galchat.service.ICharacterCardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroupTurnCheckpointServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                GroupChatReplyStep.class, com.me.galchat.domain.po.TrpgCombat.class);
    }

    @Test
    void rejectedQuestionDoesNotAdvanceCheckpoint() {
        Fixture f = fixture(GroupTurnCheckpointService.STEP_START);
        f.service().recordInteractionCommitted(new GroupChatToolCall().setId(10L).setReplyStepId(103L)
                .setToolName("askKp").setToolResult("当前步骤不允许调查员询问KP"));
        verifyNoInteractions(f.checkpointMapper(), f.messageMapper(), f.stepMapper());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "askKp,trpg_scene_action,false", "askForClarification,trpg_scene_action,false",
            "askForClarification,combat_adjudicate,false",
            "askForClarification,combat_reaction_route,true"
    })
    void committedInteractionSurvivesFailureWithoutRepeatingQuestion(
            String tool, String action, boolean route) {
        Fixture f = fixture(GroupTurnCheckpointService.STEP_START);
        var source = f.step().setActionType(action).setStepNo(2)
                .setSpeakerType("askKp".equals(tool) ? "character" : "kp")
                .setSpeakerId("askKp".equals(tool) ? 12L : null)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION);
        var root = route ? new GroupChatReplyStep().setId(100L).setTurnId(101L)
                .setActionType(GroupChatConstant.ACTION_COMBAT_ADJUDICATE)
                .setStatus(GroupChatConstant.STATUS_WAITING_INTERACTION) : source;
        if (route) source.setParentStepId(root.getId()).setOutputMessageId(null);
        var child = new GroupChatReplyStep().setId(301L).setTurnId(101L)
                .setParentStepId(root.getId()).setRootStepId(root.getId())
                .setActionType(GroupChatConstant.ACTION_TRPG_INTERACTION_RESPONSE)
                .setStatus(GroupChatConstant.STATUS_PENDING);
        when(f.stepMapper().selectById(source.getId())).thenReturn(source);
        when(f.stepMapper().selectById(child.getId())).thenReturn(child);
        if (route) when(f.stepMapper().selectById(root.getId())).thenReturn(root);
        when(f.turnMapper().selectById(101L)).thenReturn(f.turn());
        var message = new GroupChatMessage().setId(203L).setConversationId(7L)
                .setTurnId(101L).setReplyStepId(103L).setSequenceNo(5L)
                .setStatus(GroupChatConstant.STATUS_STREAMING).setContent("未完成前缀");
        when(f.messageMapper().selectById(203L)).thenReturn(message);
        org.mockito.Mockito.doAnswer(i -> {
            var inserted = i.<GroupChatMessage>getArgument(0);
            inserted.setId(204L);
            when(f.messageMapper().selectById(204L)).thenReturn(inserted);
            return 1;
        }).when(f.messageMapper()).insert(org.mockito.ArgumentMatchers.any(GroupChatMessage.class));
        // A prior showMaterial call may have a newer message ID than the streaming question.
        when(f.messageMapper().selectMaxIdByReplyStepId(103L)).thenReturn(205L);
        var boundary = new java.util.concurrent.atomic.AtomicReference<GroupTurnCheckpoint>();
        org.mockito.Mockito.doAnswer(i -> {
            boundary.set(i.getArgument(0));
            when(f.checkpointMapper().selectById(7L)).thenReturn(boundary.get());
            return 1;
        }).when(f.checkpointMapper()).upsert(org.mockito.ArgumentMatchers.any());
        var call = new java.util.concurrent.atomic.AtomicReference<GroupChatToolCall>();
        org.mockito.Mockito.doAnswer(i -> {
            call.set(i.getArgument(0)); call.get().setId(10L);
            when(f.toolCallMapper().selectById(10L)).thenReturn(call.get());
            return 1;
        }).when(f.toolCallMapper()).insert(org.mockito.ArgumentMatchers.any(GroupChatToolCall.class));
        var json = JsonMapper.builder().build();
        var interaction = new com.me.galchat.service.impl.trpg.TrpgStepInteractionService.InteractionRequest(
                301L, root.getId(), "KP_CLARIFICATION", 1,
                new com.me.galchat.groupchat.runtime.GroupActorRef("user", 31L),
                31L, "你要检查哪里？", "METHOD");
        var response = new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(
                        org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                                .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                        "ask-1", "function", tool, "{}"))).build())));
        var execution = mock(org.springframework.ai.model.tool.ToolExecutionResult.class);
        when(execution.conversationHistory()).thenReturn(List.of(
                org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                                "ask-1", tool, json.writeValueAsString(interaction)))).build()));
        new com.me.galchat.groupchat.tool.GroupToolCallStore(f.toolCallMapper(), json, f.service())
                .saveExecution(103L, response, execution);

        assertThat(boundary.get()).as("successful question must commit a recoverable boundary").isNotNull();
        assertThat(boundary.get().getCheckpointType()).isEqualTo("INTERACTION_COMMITTED");
        assertThat(boundary.get().getToolCallId()).isEqualTo(10L);
        assertThat(boundary.get().getMessageId()).isEqualTo(205L);
        var question = f.messageMapper().selectById(child.getPromptMessageId());
        assertThat(question.getContent()).isEqualTo("你要检查哪里？");
        assertThat(child.getPromptMessageId()).isEqualTo(question.getId());
        // A process interruption must still identify the source as the running step.
        assertThat(source.getStatus()).isEqualTo(GroupChatConstant.STATUS_RUNNING);
        question.setContent("中断留下的不完整文本").setStatus(GroupChatConstant.STATUS_FAILED);
        source.setStatus(GroupChatConstant.STATUS_FAILED);
        f.service().restore(f.turn(), source);

        assertThat(source.getStatus()).isEqualTo(route ? GroupChatConstant.STATUS_COMPLETED
                : GroupChatConstant.STATUS_WAITING_INTERACTION);
        assertThat(root.getStatus()).isEqualTo(GroupChatConstant.STATUS_WAITING_INTERACTION);
        assertThat(source.getOutputMessageId()).isEqualTo(question.getId());
        var resets = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(f.stepMapper(), org.mockito.Mockito.times(route ? 2 : 1))
                .update(org.mockito.ArgumentMatchers.isNull(), resets.capture());
        // Verify the persisted output link as well as the in-memory step.
        resets.getAllValues().forEach(wrapper -> wrapper.getSqlSet());
        assertThat(resets.getAllValues().getFirst().getParamNameValuePairs().values()).contains(question.getId());
        assertThat(question.getContent()).isEqualTo("你要检查哪里？");
        assertThat(question.getStatus()).isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        assertThat(child.getStatus()).isEqualTo(GroupChatConstant.STATUS_PENDING);
        verify(f.toolCallMapper()).deleteAfterCheckpoint(103L, 10L);
        verify(f.messageMapper()).deleteAfterCheckpoint(103L, 205L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0L, 1L})
    void restoringStepKeepsFinishRequestOnlyWhenItsToolCallSurvives(long remainingCalls) {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        var completions = mock(com.me.galchat.mapper.TrpgCompletionMapper.class);
        fixture.service().setCompletionMapper(completions);
        when(completions.selectById(7L)).thenReturn(new com.me.galchat.domain.po.TrpgCompletion()
                .setConversationId(7L).setTurnId(fixture.turn().getId()));
        when(fixture.stepMapper().selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(fixture.step()));
        when(fixture.toolCallMapper().selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(remainingCalls);
        fixture.service().restore(fixture.turn(), fixture.step());
        verify(completions, org.mockito.Mockito.times(remainingCalls == 0 ? 1 : 0)).deleteById(7L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "startCombat,8,success,true", "startCombat,9,success,true",
            "startCombat,10,success,false", "startCombat,8,error,false",
            "startCombat,8,other-combat,false",
            "markCombatFinished,8,success,true", "markCombatFinished,9,success,true",
            "markCombatFinished,10,success,false", "markCombatFinished,8,error,false",
            "markCombatFinished,8,other-combat,false"
    })
    void combatControlRecoveryPreservesOnlySuccessfulRequestsWithinCheckpoint(
            String tool, long callId, String resultKind, boolean preserve) {
        Fixture fixture = fixture(GroupTurnCheckpointService.PAUSED);
        var combats = mock(com.me.galchat.mapper.TrpgCombatMapper.class);
        var lifecycle = new com.me.galchat.service.impl.trpg.TrpgCombatLifecycleService(
                mock(GroupConversationService.class), mock(GroupReplyPlanService.class),
                mock(com.me.galchat.mapper.GroupReplyPlanMapper.class), fixture.turnMapper(),
                fixture.toolCallMapper(), fixture.stepMapper(), fixture.messageMapper(),
                mock(com.me.galchat.mapper.CocCharacterMapper.class), combats,
                mock(com.me.galchat.service.impl.trpg.TrpgQuickNpcTemplateService.class),
                JsonMapper.builder().build());
        boolean start = "startCombat".equals(tool);
        var combat = new com.me.galchat.domain.po.TrpgCombat().setId(200L)
                .setStatus(start ? GroupChatConstant.COMBAT_STATUS_START_REQUESTED
                        : GroupChatConstant.COMBAT_STATUS_ACTIVE);
        if (start) combat.setStartRequestedStepId(fixture.step().getId());
        else combat.setFinishRequestedStepId(fixture.step().getId());
        when(combats.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(combat));
        String result = switch (resultKind) {
            case "success" -> "{\"combatId\":200}";
            case "other-combat" -> "{\"combatId\":201}";
            default -> "当前步骤不允许调用";
        };
        var retained = new java.util.ArrayList<>(List.of(new GroupChatToolCall()
                .setId(callId).setReplyStepId(fixture.step().getId())
                .setToolName(tool).setToolResult(result)));
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        when(fixture.toolCallMapper().selectList(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> restored.get() ? List.copyOf(retained) : List.of());
        when(fixture.toolCallMapper().deleteAfterCheckpoint(fixture.step().getId(), 9L))
                .thenAnswer(invocation -> {
                    retained.removeIf(call -> call.getId() > 9L);
                    restored.set(true);
                    return 1;
                });

        fixture.service().restore(fixture.turn(), fixture.step());
        lifecycle.clearControlMarkersForRetry(fixture.step().getId());

        if (start) {
            assertThat(combat.getStatus()).isEqualTo(preserve
                    ? GroupChatConstant.COMBAT_STATUS_START_REQUESTED
                    : GroupChatConstant.COMBAT_STATUS_CANCELLED);
        } else {
            assertThat(combat.getFinishRequestedStepId())
                    .isEqualTo(preserve ? fixture.step().getId() : null);
        }
        if (preserve) {
            verify(combats, org.mockito.Mockito.never()).updateById(
                    org.mockito.ArgumentMatchers.any(com.me.galchat.domain.po.TrpgCombat.class));
            verify(combats, org.mockito.Mockito.never()).update(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    void invalidTurnStepContextCannotTriggerDestructiveRecovery() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        fixture.step().setTurnId(999L);

        assertThatThrownBy(() -> fixture.service().restore(
                fixture.turn(), fixture.step()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("行动轮检查点上下文不完整");

        verifyNoInteractions(
                fixture.messageMapper(), fixture.toolCallMapper());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "missing", "different-turn", "different-step",
            "completed", "unknown-type", "missing-type"
    })
    void unusableCheckpointRejectsRetryWithoutChangingState(String scenario) {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        GroupTurnCheckpoint checkpoint = new GroupTurnCheckpoint()
                .setConversationId(7L)
                .setTurnId(101L)
                .setReplyStepId(103L)
                .setCheckpointType(GroupTurnCheckpointService.STEP_START)
                .setMessageId(202L)
                .setToolCallId(9L);
        switch (scenario) {
            case "missing" -> checkpoint = null;
            case "different-turn" -> checkpoint.setTurnId(100L);
            case "different-step" -> checkpoint.setReplyStepId(102L);
            case "completed" -> checkpoint.setCheckpointType(
                    GroupTurnCheckpointService.COMPLETED);
            case "unknown-type" -> checkpoint.setCheckpointType("UNKNOWN");
            case "missing-type" -> checkpoint.setCheckpointType(null);
            default -> throw new AssertionError(scenario);
        }
        when(fixture.checkpointMapper().selectById(7L))
                .thenReturn(checkpoint);
        fixture.step().setErrorMessage("模型调用失败");

        assertThatThrownBy(() -> fixture.service().restore(
                fixture.turn(), fixture.step()))
                .isInstanceOf(TurnCheckpointUnavailableException.class)
                .hasMessage("未找到匹配的可恢复检查点，无法重试。请打开「跑团工具 → 存档」，使用「回退至上一轮」恢复后继续。");

        assertThat(fixture.step().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_FAILED);
        assertThat(fixture.step().getOutputMessageId()).isEqualTo(203L);
        assertThat(fixture.step().getErrorMessage()).isEqualTo("模型调用失败");
        assertThat(fixture.turn().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_FAILED);
        verifyNoInteractions(fixture.messageMapper(), fixture.toolCallMapper(),
                fixture.stepMapper(), fixture.turnMapper(),
                fixture.characterCardService(), fixture.diceRollSummaryMapper(),
                fixture.diceMessageCodec());
        verify(fixture.checkpointMapper()).selectById(7L);
        org.mockito.Mockito.verifyNoMoreInteractions(fixture.checkpointMapper());
    }

    @Test
    void restoresOnlyTheFailedStepTailAfterPausedCheckpoint() {
        GroupTurnCheckpointMapper checkpointMapper =
                mock(GroupTurnCheckpointMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatToolCallMapper toolCallMapper =
                mock(GroupChatToolCallMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupTurnCheckpointService service =
                new GroupTurnCheckpointService(mock(com.me.galchat.service.impl.group.GroupConversationService.class),
                        checkpointMapper,
                        messageMapper,
                        toolCallMapper,
                        stepMapper,
                        turnMapper,
                        mock(DiceRollSummaryMapper.class),
                        mock(com.me.galchat.groupchat.dice
                                .DiceRollMessageCodec.class),
                        mock(ICharacterCardService.class),
                        JsonMapper.builder().build(), mock(GroupChatFavorRollbackService.class),
                        mock(com.me.galchat.service.impl.trpg.TrpgEquipmentService.class),
                        mock(com.me.galchat.service.impl.trpg.TrpgMaterialRecoveryService.class),
                        mock(com.me.galchat.service.impl.trpg.TrpgInvestigatorSuspensionService.class), mock(com.me.galchat.service.impl.trpg.TrpgSceneFinishRecoveryService.class), mock(com.me.galchat.service.impl.trpg.TrpgChildSceneCommandService.class), mock(com.me.galchat.service.impl.trpg.TrpgToolStateRecoveryService.class), mock(com.me.galchat.service.impl.trpg.TrpgSelectionRecoveryService.class));
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(103L)
                .setTurnId(101L)
                .setOutputMessageId(203L)
                .setErrorMessage("模型调用失败")
                .setStatus(GroupChatConstant.STATUS_FAILED);
        when(checkpointMapper.selectById(7L)).thenReturn(
                new GroupTurnCheckpoint()
                        .setConversationId(7L)
                        .setTurnId(101L)
                        .setReplyStepId(103L)
                        .setCheckpointType(
                                GroupTurnCheckpointService.PAUSED)
                        .setMessageId(202L)
                        .setToolCallId(9L));

        service.restore(turn, step);

        assertThat(step.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        assertThat(step.getOutputMessageId()).isNull();
        assertThat(step.getErrorMessage()).isNull();
        assertThat(turn.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_RUNNING);
        verify(messageMapper).deleteAfterCheckpoint(103L, 202L);
        verify(toolCallMapper).deleteAfterCheckpoint(103L, 9L);
        verify(stepMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
        verify(turnMapper).updateById(turn);
    }

    @Test
    void committedPendingDiceRestoresTheWaitingBoundary() {
        Fixture fixture = fixture(
                GroupTurnCheckpointService.TOOL_COMMITTED);
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(committedDiceCall()), List.of());
        when(fixture.diceRollSummaryMapper().selectById(501L))
                .thenReturn(new DiceRollSummary()
                        .setId(501L)
                        .setStatus(DiceRollConstant.STATUS_PENDING));
        GroupChatMessage message = new GroupChatMessage()
                .setId(203L)
                .setReplyStepId(103L)
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        when(fixture.messageMapper().selectById(203L))
                .thenReturn(message);
        when(fixture.diceMessageCodec().encode(501L, List.of(1)))
                .thenReturn("{\"summaryId\":501,\"roundNos\":[1]}");

        fixture.service().restore(
                fixture.turn(), fixture.step());

        assertThat(fixture.step().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(fixture.turn().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_WAITING_DICE);
        assertThat(message.getMessageKind())
                .isEqualTo(GroupChatConstant.MESSAGE_DICE_ROLL);
        assertThat(message.getStatus())
                .isEqualTo(GroupChatConstant.STATUS_COMPLETED);
        assertThat(message.getContent())
                .isEqualTo("{\"summaryId\":501,\"roundNos\":[1]}");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"no-output", "missing-message", "wrong-step"})
    void unavailableDiceOutputOffersTurnRollbackInsteadOfAnotherRetry(String scenario) {
        Fixture fixture = fixture(GroupTurnCheckpointService.TOOL_COMMITTED);
        when(fixture.toolCallMapper().selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(committedDiceCall()), List.of());
        if ("no-output".equals(scenario)) {
            fixture.step().setOutputMessageId(null);
        } else if ("wrong-step".equals(scenario)) {
            when(fixture.messageMapper().selectById(203L)).thenReturn(
                    new GroupChatMessage().setId(203L).setReplyStepId(999L));
        }

        var events = new GroupGenerationStreamRegistry().start(7L, "dice-recovery",
                reactor.core.publisher.Flux.<com.me.galchat.domain.vo.GroupChatEvent>defer(() -> {
                    fixture.service().restore(fixture.turn(), fixture.step());
                    return reactor.core.publisher.Flux.empty();
                })).collectList().block();

        var failure = events.stream()
                .filter(event -> GroupChatConstant.EVENT_GENERATION_FAILED.equals(event.getEventType()))
                .findFirst().orElseThrow();
        assertThat(failure.getErrorDetail().getCode()).isEqualTo(TurnCheckpointUnavailableException.CODE);
        assertThat(failure.getErrorDetail().getRetryable()).isFalse();
        assertThat(failure.getError()).contains("骰点", "回退至上一轮");
        assertThat(fixture.turn().getStatus()).isEqualTo(GroupChatConstant.STATUS_FAILED);
    }

    @Test
    void committedCompletedDiceResumesTheSameStep() {
        Fixture fixture = fixture(
                GroupTurnCheckpointService.WAITING_DICE);
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(committedDiceCall()), List.of());
        when(fixture.diceRollSummaryMapper().selectById(501L))
                .thenReturn(new DiceRollSummary()
                        .setId(501L)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));

        fixture.service().restore(
                fixture.turn(), fixture.step());

        assertThat(fixture.step().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_PENDING);
        assertThat(fixture.turn().getStatus())
                .isEqualTo(GroupChatConstant.STATUS_RUNNING);
    }

    @Test
    void recordsTheLatestMessageAndToolBoundaryForTheActiveStep() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        when(fixture.toolCallMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(9L);
        when(fixture.messageMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(212L);
        GroupTurnCheckpointMapper checkpointMapper =
                fixture.checkpointMapper();

        fixture.service().recordBoundary(
                fixture.turn(), fixture.step(),
                GroupTurnCheckpointService.PAUSED);

        ArgumentCaptor<GroupTurnCheckpoint> saved =
                ArgumentCaptor.forClass(GroupTurnCheckpoint.class);
        verify(checkpointMapper).upsert(saved.capture());
        assertThat(saved.getValue())
                .extracting(
                        GroupTurnCheckpoint::getConversationId,
                        GroupTurnCheckpoint::getTurnId,
                        GroupTurnCheckpoint::getReplyStepId,
                        GroupTurnCheckpoint::getCheckpointType,
                        GroupTurnCheckpoint::getMessageId,
                        GroupTurnCheckpoint::getToolCallId)
                .containsExactly(
                        7L, 101L, 103L,
                        GroupTurnCheckpointService.PAUSED,
                        212L, 9L);
    }

    @Test
    void initializingResumedStepPreservesEarlierInteractionHistory() {
        Fixture fixture = fixture(GroupTurnCheckpointService.COMPLETED);
        when(fixture.checkpointMapper().selectById(7L)).thenReturn(
                new GroupTurnCheckpoint()
                        .setConversationId(7L)
                        .setTurnId(101L)
                        .setReplyStepId(301L)
                        .setCheckpointType(
                                GroupTurnCheckpointService.COMPLETED));
        when(fixture.messageMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(212L);
        when(fixture.toolCallMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(9L);

        fixture.service().initializeStep(
                fixture.turn(), fixture.step());

        ArgumentCaptor<GroupTurnCheckpoint> saved =
                ArgumentCaptor.forClass(GroupTurnCheckpoint.class);
        verify(fixture.checkpointMapper()).upsert(saved.capture());
        assertThat(saved.getValue())
                .extracting(
                        GroupTurnCheckpoint::getCheckpointType,
                        GroupTurnCheckpoint::getMessageId,
                        GroupTurnCheckpoint::getToolCallId)
                .containsExactly(
                        GroupTurnCheckpointService.STEP_START,
                        212L, 9L);
    }

    @Test
    void committedToolRecordsBothDurableHighWaterMarks() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        when(fixture.stepMapper().selectById(103L))
                .thenReturn(fixture.step());
        when(fixture.turnMapper().selectById(101L))
                .thenReturn(fixture.turn());
        when(fixture.messageMapper().selectMaxIdByReplyStepId(103L))
                .thenReturn(212L);

        fixture.service().recordToolCommitted(103L, 9L);

        ArgumentCaptor<GroupTurnCheckpoint> saved =
                ArgumentCaptor.forClass(GroupTurnCheckpoint.class);
        verify(fixture.checkpointMapper()).upsert(saved.capture());
        assertThat(saved.getValue())
                .extracting(
                        GroupTurnCheckpoint::getCheckpointType,
                        GroupTurnCheckpoint::getMessageId,
                        GroupTurnCheckpoint::getToolCallId)
                .containsExactly(
                        GroupTurnCheckpointService.TOOL_COMMITTED,
                        212L, 9L);
    }

    @Test
    void rollsBackSuccessfulAttributeChangeBeforeDeletingFailedStepTail() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        GroupChatToolCall adjustment = new GroupChatToolCall()
                .setId(10L)
                .setReplyStepId(103L)
                .setToolName("adjustBasicAttributes")
                .setToolResult("""
                        {"characterName":"林恩",
                         "changes":{"STR":{"before":50,"after":60}},
                         "damageBonus":"0","build":0}
                        """);
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(adjustment));

        fixture.service().restore(fixture.turn(), fixture.step());

        ArgumentCaptor<KpCharacterAttributeDTOs.Result> result =
                ArgumentCaptor.forClass(
                        KpCharacterAttributeDTOs.Result.class);
        var ordered = org.mockito.Mockito.inOrder(
                fixture.characterCardService(),
                fixture.toolCallMapper());
        ordered.verify(fixture.characterCardService())
                .rollbackBasicAttributeAdjustment(
                        org.mockito.ArgumentMatchers.eq(7L),
                        result.capture());
        ordered.verify(fixture.toolCallMapper())
                .deleteAfterCheckpoint(103L, 9L);
        assertThat(result.getValue().characterName()).isEqualTo("林恩");
        assertThat(result.getValue().changes().get("STR"))
                .isEqualTo(new KpCharacterAttributeDTOs.ValueChange(50, 60));
    }

    @Test
    void skipsFailedAttributeToolResponseDuringRecovery() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        GroupChatToolCall failedAdjustment = new GroupChatToolCall()
                .setId(10L)
                .setReplyStepId(103L)
                .setToolName("adjustBasicAttributes")
                .setToolResult("Error invoking tool: 人物卡不存在");
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(failedAdjustment));

        fixture.service().restore(
                fixture.turn(), fixture.step());

        verifyNoInteractions(fixture.characterCardService());
        verify(fixture.toolCallMapper())
                .deleteAfterCheckpoint(103L, 9L);
    }

    @Test
    void skipsFailedAttributeResponseAndStillRollsBackSuccessfulOne() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        GroupChatToolCall failedAdjustment = new GroupChatToolCall()
                .setId(11L)
                .setReplyStepId(103L)
                .setToolName("adjustBasicAttributes")
                .setToolResult("Error invoking tool: 修正值无效");
        GroupChatToolCall successfulAdjustment = new GroupChatToolCall()
                .setId(10L)
                .setReplyStepId(103L)
                .setToolName("adjustBasicAttributes")
                .setToolResult("""
                        {"characterName":"林恩",
                         "changes":{"STR":{"before":50,"after":60}},
                         "damageBonus":"0","build":0}
                        """);
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        failedAdjustment, successfulAdjustment));

        fixture.service().restore(fixture.turn(), fixture.step());

        ArgumentCaptor<KpCharacterAttributeDTOs.Result> result =
                ArgumentCaptor.forClass(
                        KpCharacterAttributeDTOs.Result.class);
        verify(fixture.characterCardService())
                .rollbackBasicAttributeAdjustment(
                        org.mockito.ArgumentMatchers.eq(7L),
                        result.capture());
        assertThat(result.getValue().characterName()).isEqualTo("林恩");
    }

    @Test
    void malformedJsonAttributeRecordStillAbortsRecovery() {
        Fixture fixture = fixture(GroupTurnCheckpointService.STEP_START);
        GroupChatToolCall malformedAdjustment = new GroupChatToolCall()
                .setId(10L)
                .setReplyStepId(103L)
                .setToolName("adjustBasicAttributes")
                .setToolResult("{\"characterName\":\"林恩\"");
        when(fixture.toolCallMapper().selectList(
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(malformedAdjustment));

        assertThatThrownBy(() -> fixture.service().restore(
                fixture.turn(), fixture.step()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("基础属性调整记录无法解析，已中止重试");
    }

    private Fixture fixture(String checkpointType) {
        GroupTurnCheckpointMapper checkpointMapper =
                mock(GroupTurnCheckpointMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupChatToolCallMapper toolCallMapper =
                mock(GroupChatToolCallMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        DiceRollSummaryMapper diceRollSummaryMapper =
                mock(DiceRollSummaryMapper.class);
        com.me.galchat.groupchat.dice.DiceRollMessageCodec codec =
                mock(com.me.galchat.groupchat.dice
                        .DiceRollMessageCodec.class);
        ICharacterCardService characterCardService =
                mock(ICharacterCardService.class);
        GroupTurnCheckpointService service =
                new GroupTurnCheckpointService(mock(com.me.galchat.service.impl.group.GroupConversationService.class),
                        checkpointMapper,
                        messageMapper,
                        toolCallMapper,
                        stepMapper,
                        turnMapper,
                        diceRollSummaryMapper,
                        codec,
                        characterCardService,
                        JsonMapper.builder().build(), mock(GroupChatFavorRollbackService.class),
                        mock(com.me.galchat.service.impl.trpg.TrpgEquipmentService.class),
                        mock(com.me.galchat.service.impl.trpg.TrpgMaterialRecoveryService.class),
                        mock(com.me.galchat.service.impl.trpg.TrpgInvestigatorSuspensionService.class), mock(com.me.galchat.service.impl.trpg.TrpgSceneFinishRecoveryService.class), mock(com.me.galchat.service.impl.trpg.TrpgChildSceneCommandService.class), mock(com.me.galchat.service.impl.trpg.TrpgToolStateRecoveryService.class), mock(com.me.galchat.service.impl.trpg.TrpgSelectionRecoveryService.class));
        GroupChatTurn turn = new GroupChatTurn()
                .setId(101L)
                .setConversationId(7L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(103L)
                .setTurnId(101L)
                .setOutputMessageId(203L)
                .setStatus(GroupChatConstant.STATUS_FAILED);
        when(checkpointMapper.selectById(7L)).thenReturn(
                new GroupTurnCheckpoint()
                        .setConversationId(7L)
                        .setTurnId(101L)
                        .setReplyStepId(103L)
                        .setCheckpointType(checkpointType)
                        .setMessageId(202L)
                        .setToolCallId(9L));
        return new Fixture(
                service, checkpointMapper, messageMapper, toolCallMapper,
                stepMapper, turnMapper, diceRollSummaryMapper, codec,
                characterCardService,
                turn, step);
    }

    private GroupChatToolCall committedDiceCall() {
        return new GroupChatToolCall()
                .setId(9L)
                .setReplyStepId(103L)
                .setToolName("requestCheck")
                .setDiceRollSummaryId(501L)
                .setToolResult("""
                        {"summary":{"id":501,"status":"PENDING"},
                         "results":[{"roundNo":1}]}
                        """);
    }

    private record Fixture(
            GroupTurnCheckpointService service,
            GroupTurnCheckpointMapper checkpointMapper,
            GroupChatMessageMapper messageMapper,
            GroupChatToolCallMapper toolCallMapper,
            GroupChatReplyStepMapper stepMapper,
            GroupChatTurnMapper turnMapper,
            DiceRollSummaryMapper diceRollSummaryMapper,
            com.me.galchat.groupchat.dice.DiceRollMessageCodec
                    diceMessageCodec,
            ICharacterCardService characterCardService,
            GroupChatTurn turn,
            GroupChatReplyStep step) {
    }
}
