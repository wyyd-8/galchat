package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgChildSceneCommandServiceTest {

    @Test
    void requestingChildSceneDoesNotActivateItBeforeKpTurnCompletes() {
        Fixture fixture = fixture();

        var result = fixture.service().startChildScene(
                7L, 51L, "临时藏身处", List.of("亨利", "艾琳"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.message()).isEqualTo(
                "已接受子场景请求“临时藏身处”，调查员亨利、艾琳将在本轮结束后进入该场景。");
        assertThat(fixture.conversation().getActiveReplyPlanId())
                .isEqualTo(fixture.parent().getId());
        verify(fixture.planMapper(), never())
                .insert(any(GroupReplyPlan.class));
    }

    @Test
    void resumesWaitingInvestigatorsForTheNextRound() {
        Fixture fixture = fixture();
        fixture.investigatorItems().get(1)
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_WAITING);

        var result = fixture.service()
                .resumeWaitingInvestigators(
                        7L, 51L, List.of("艾琳"));

        assertThat(result.message())
                .isEqualTo("艾琳已结束等待，将从下一轮开始正常参与行动。");
        assertThat(fixture.investigatorItems().get(1)
                .getParticipantStatus())
                .isEqualTo(GroupChatConstant.PARTICIPANT_ACTIVE);
        verify(fixture.itemMapper()).updateById(
                fixture.investigatorItems().get(1));
        verify(fixture.progressStore()).clearReady(
                7L, 31L, 109L);
    }

    @Test
    void activatesRecordedChildSceneAfterKpTurnCompletes() {
        Fixture fixture = fixture();
        when(fixture.stepMapper().selectList(any()))
                .thenReturn(List.of(fixture.step()));
        when(fixture.toolCallMapper().selectList(any()))
                .thenReturn(List.of(new GroupChatToolCall()
                        .setReplyStepId(fixture.step().getId())
                        .setToolName("startChildScene")
                        .setToolArguments("""
                                {"childSceneName":"临时藏身处","investigatorNames":["亨利","艾琳"]}
                                """)
                        .setToolResult("{\"accepted\":true,\"message\":\"已接受子场景请求\"}")));
        when(fixture.planMapper().insert(any(GroupReplyPlan.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupReplyPlan>getArgument(0)
                            .setId(41L);
                    return 1;
                });

        boolean activated = fixture.service()
                .finalizeStartAfterTurn(
                        fixture.conversation(), fixture.turn());

        assertThat(activated).isTrue();
        assertThat(fixture.conversation().getActiveReplyPlanId())
                .isEqualTo(41L);
        verify(fixture.progressStore()).clearReady(7L, 31L, 101L);
        verify(fixture.progressStore()).clearReady(7L, 31L, 109L);
    }

    @Test
    void activatesSeveralRecordedChildScenesInCallOrder() {
        Fixture fixture = fixture();
        when(fixture.stepMapper().selectList(any()))
                .thenReturn(List.of(fixture.step()));
        when(fixture.toolCallMapper().selectList(any()))
                .thenReturn(List.of(
                        new GroupChatToolCall()
                                .setReplyStepId(fixture.step().getId())
                                .setToolName("startChildScene")
                                .setToolArguments("""
                                        {"childSceneName":"钟楼","investigatorNames":["亨利"]}
                                        """)
                                .setToolResult("{\"accepted\":true,\"message\":\"已接受子场景请求\"}"),
                        new GroupChatToolCall()
                                .setReplyStepId(fixture.step().getId())
                                .setToolName("startChildScene")
                                .setToolArguments("""
                                        {"childSceneName":"地下室","investigatorNames":["艾琳"]}
                                        """)
                                .setToolResult("{\"accepted\":true,\"message\":\"已接受子场景请求\"}")));
        java.util.concurrent.atomic.AtomicLong ids =
                new java.util.concurrent.atomic.AtomicLong(40L);
        when(fixture.planMapper().insert(any(GroupReplyPlan.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupReplyPlan>getArgument(0)
                            .setId(ids.incrementAndGet());
                    return 1;
                });

        boolean activated = fixture.service()
                .finalizeStartAfterTurn(
                        fixture.conversation(), fixture.turn());

        assertThat(activated).isTrue();
        assertThat(fixture.conversation().getActiveReplyPlanId())
                .isEqualTo(41L);
        org.mockito.ArgumentCaptor<GroupReplyPlan> plans =
                org.mockito.ArgumentCaptor.forClass(GroupReplyPlan.class);
        verify(fixture.planMapper(), org.mockito.Mockito.times(2))
                .insert(plans.capture());
        assertThat(plans.getAllValues())
                .extracting(GroupReplyPlan::getId,
                        GroupReplyPlan::getNextPlanId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(41L, 42L),
                        org.assertj.core.groups.Tuple.tuple(42L, null));
    }

    @Test
    void allowsAnotherDisjointChildSceneRequestFromTheSameKpStep() {
        Fixture fixture = fixture();
        when(fixture.toolCallMapper().selectList(any()))
                .thenReturn(List.of(new GroupChatToolCall()
                        .setReplyStepId(fixture.step().getId())
                        .setToolName("startChildScene")
                        .setToolArguments("""
                                {"childSceneName":"临时藏身处","investigatorNames":["亨利"]}
                                """)
                        .setToolResult("{\"accepted\":true,\"message\":\"已接受子场景请求\"}")));

        var result = fixture.service().startChildScene(
                7L, 51L, "另一条林间小路", List.of("艾琳"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.message()).isEqualTo(
                "已接受子场景请求“另一条林间小路”，调查员艾琳将在本轮结束后进入该场景。");
    }

    @Test
    void offersChildSceneToolWithoutPresetDescendantLocations() {
        Fixture fixture = fixture();

        assertThat(fixture.service().canStartChildScene(
                fixture.conversation())).isTrue();
    }

    @Test
    void doesNotOfferChildSceneToolInsideAChildScene() {
        Fixture fixture = fixture();
        fixture.parent().setParentPlanId(20L);

        assertThat(fixture.service().canStartChildScene(
                fixture.conversation())).isFalse();
        assertThat(fixture.service().isActiveChildScene(
                fixture.conversation())).isTrue();
    }

    @Test
    void rejectsMovingAnInvestigatorWhoIsAlreadyInAChildScene() {
        Fixture fixture = fixture();
        fixture.parent().setParentPlanId(20L);

        assertThatThrownBy(() -> fixture.service().startChildScene(
                7L, 51L, "另一处区域", List.of("亨利")))
                .hasMessage("当前调查员已处于子场景中，请结束当前场景后再进行后续切换");
    }

    @Test
    void rejectsChildSceneNamesLongerThanStorageLimit() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service().startChildScene(
                7L, 51L, "临".repeat(201), List.of("亨利")))
                .hasMessageContaining("200");
    }

    @Test
    void rejectedConflictingCallDoesNotPreventAcceptedChildFromStarting() {
        Fixture fixture = fixture();
        when(fixture.stepMapper().selectList(any())).thenReturn(List.of(fixture.step()));
        when(fixture.toolCallMapper().selectList(any())).thenReturn(List.of(
                recordedStart("钟楼", "亨利", "已创建子场景“钟楼”，调查员亨利将进入该场景。"),
                recordedStart("地下室", "亨利", "同一调查员不能在本步骤前往多个子场景")));
        when(fixture.planMapper().insert(any(GroupReplyPlan.class))).thenAnswer(invocation -> {
            invocation.<GroupReplyPlan>getArgument(0).setId(41L);
            return 1;
        });

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                assertThat(fixture.service().finalizeStartAfterTurn(
                        fixture.conversation(), fixture.turn())).isTrue())).isNull();
        assertThat(fixture.conversation().getActiveReplyPlanId()).isEqualTo(41L);
        verify(fixture.planMapper()).insert(any(GroupReplyPlan.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "当前场景回复计划已变化", "null", "{}", "{\"accepted\":false}", "已创建子场景"
    })
    void failedOrUnrecognizedResultDoesNotCreateAChildScene(String result) {
        Fixture fixture = fixture();
        when(fixture.stepMapper().selectList(any())).thenReturn(List.of(fixture.step()));
        when(fixture.toolCallMapper().selectList(any())).thenReturn(List.of(
                recordedStart("钟楼", "亨利", result)));

        assertThat(fixture.service().finalizeStartAfterTurn(
                fixture.conversation(), fixture.turn())).isFalse();
        verify(fixture.planMapper(), never()).insert(any(GroupReplyPlan.class));
    }

    @Test
    void rejectedCallDoesNotReserveDestinationOrInvestigators() {
        Fixture fixture = fixture();
        when(fixture.toolCallMapper().selectList(any())).thenReturn(List.of(
                recordedStart("钟楼", "亨利", "当前场景回复计划已变化")));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                fixture.service().startChildScene(7L, 51L, "钟楼", List.of("亨利")))).isNull();
    }

    @Test
    void actualToolExecutionKeepsRejectedHistoryButStartsOnlyAcceptedChildren() {
        Fixture fixture = fixture();
        var rows = new java.util.ArrayList<GroupChatToolCall>();
        var json = new ObjectMapper();
        when(fixture.toolCallMapper().selectList(any())).thenAnswer(invocation -> List.copyOf(rows));
        when(fixture.toolCallMapper().insert(any(GroupChatToolCall.class))).thenAnswer(invocation -> {
            GroupChatToolCall row = invocation.getArgument(0);
            row.setId((long) rows.size() + 1);
            rows.add(row);
            return 1;
        });
        var store = new com.me.galchat.groupchat.tool.GroupToolCallStore(
                fixture.toolCallMapper(), json,
                mock(com.me.galchat.service.impl.group.GroupTurnCheckpointService.class));
        var manager = new com.me.galchat.groupchat.tool.RecordingGroupToolCallingManager(
                org.springframework.ai.model.tool.DefaultToolCallingManager.builder().build(), store,
                mock(org.springframework.transaction.support.TransactionTemplate.class));
        var options = org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(
                        new com.me.galchat.tool.KpChildSceneTools(fixture.service())))
                .toolContext(java.util.Map.of(
                        com.me.galchat.constant.ChatToolContextConstant.ACTOR_TYPE_KEY, GroupChatConstant.ACTOR_KP,
                        com.me.galchat.constant.ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 7L,
                        com.me.galchat.constant.ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 51L)).build();
        var prompt = new org.springframework.ai.chat.prompt.Prompt("创建子场景", options);
        for (var request : List.of(
                recordedStart("钟楼", "亨利", ""),
                recordedStart("地下室", "亨利", ""),
                recordedStart("地下室", "艾琳", ""))) {
            var response = new org.springframework.ai.chat.model.ChatResponse(List.of(
                    new org.springframework.ai.chat.model.Generation(
                            org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                                    .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                            "call-" + rows.size(), "function", "startChildScene",
                                            request.getToolArguments()))).build())));
            manager.executeToolCalls(prompt, response);
        }
        assertThat(rows).hasSize(3);
        assertThat(json.readTree(rows.getFirst().getToolResult()).path("accepted").asBoolean()).isTrue();
        assertThat(rows.get(1).getToolResult()).contains("同一调查员不能在本步骤前往多个子场景");
        assertThat(json.readTree(rows.getLast().getToolResult()).path("accepted").asBoolean()).isTrue();
        when(fixture.stepMapper().selectList(any())).thenReturn(List.of(fixture.step()));
        var ids = new java.util.concurrent.atomic.AtomicLong(40L);
        when(fixture.planMapper().insert(any(GroupReplyPlan.class))).thenAnswer(invocation -> {
            invocation.<GroupReplyPlan>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        });

        assertThat(fixture.service().finalizeStartAfterTurn(fixture.conversation(), fixture.turn())).isTrue();
        var plans = org.mockito.ArgumentCaptor.forClass(GroupReplyPlan.class);
        verify(fixture.planMapper(), org.mockito.Mockito.times(2)).insert(plans.capture());
        assertThat(plans.getAllValues()).extracting(GroupReplyPlan::getId).containsExactly(41L, 42L);
        assertThat(fixture.conversation().getActiveReplyPlanId()).isEqualTo(41L);
    }

    @Test
    void recognizesJsonEncodedSuccessFromOldSaves() {
        Fixture fixture = fixture();
        when(fixture.toolCallMapper().selectList(any())).thenReturn(List.of(recordedStart(
                "钟楼", "亨利", new ObjectMapper().writeValueAsString(
                        "已创建子场景“钟楼”，调查员亨利将进入该场景。"))));

        assertThatThrownBy(() -> fixture.service().startChildScene(7L, 51L, "钟楼", List.of("艾琳")))
                .hasMessage("同一目的地只应调用一次子场景工具");
        assertThatThrownBy(() -> fixture.service().startChildScene(7L, 51L, "地下室", List.of("亨利")))
                .hasMessage("同一调查员不能在本步骤前往多个子场景");
    }

    private GroupChatToolCall recordedStart(String scene, String investigator, String result) {
        return new GroupChatToolCall().setReplyStepId(51L).setToolName("startChildScene")
                .setToolArguments(new ObjectMapper().writeValueAsString(java.util.Map.of(
                        "childSceneName", scene, "investigatorNames", List.of(investigator))))
                .setToolResult(result);
    }

    private Fixture fixture() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatToolCallMapper toolCallMapper =
                mock(GroupChatToolCallMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgChildScenePlanService planService =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper,
                        mock(TrpgRuntimeChildSceneMapper.class));
        TrpgSceneProgressStore progressStore =
                mock(TrpgSceneProgressStore.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(5L)
                .setActiveReplyPlanId(31L)
                .setMode(GroupChatConstant.MODE_TRPG);
        GroupReplyPlan parent = new GroupReplyPlan()
                .setId(31L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(21L);
        List<GroupReplyPlanItem> investigatorItems = List.of(
                item(GroupChatConstant.ACTOR_USER, 101L, 1),
                item(GroupChatConstant.ACTOR_CHARACTER, 9L, 2));
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(51L).setTurnId(61L)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        GroupChatTurn turn = new GroupChatTurn().setId(61L)
                .setConversationId(7L).setPlanId(31L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE);
        when(stepMapper.selectById(51L)).thenReturn(step);
        when(turnMapper.selectById(61L)).thenReturn(turn);
        when(planMapper.selectById(31L)).thenReturn(parent);
        when(itemMapper.selectList(any())).thenReturn(
                new java.util.ArrayList<>() {{
                    addAll(investigatorItems);
                    add(new GroupReplyPlanItem()
                            .setActorType(GroupChatConstant.ACTOR_KP));
                }});
        when(itemMapper.updateById(any(GroupReplyPlanItem.class))).thenReturn(1);
        TrpgChildSceneCommandService service =
                new TrpgChildSceneCommandService(
                        conversationService, stepMapper, turnMapper,
                        planMapper, itemMapper,
                        planService, progressStore,
                        toolCallMapper, new ObjectMapper());
        return new Fixture(
                service, stepMapper, toolCallMapper, planMapper,
                itemMapper, conversation, step, turn,
                parent, investigatorItems, progressStore);
    }

    private GroupReplyPlanItem item(
            String actorType, Long actorId, int order) {
        return new GroupReplyPlanItem()
                .setId((long) order)
                .setPlanId(31L)
                .setItemOrder(order)
                .setActorType(actorType)
                .setActorId(actorId)
                .setSubjectCharacterId(
                        GroupChatConstant.ACTOR_USER.equals(actorType)
                                ? actorId : actorId + 100L)
                .setSubjectCharacterName(
                        GroupChatConstant.ACTOR_USER.equals(actorType)
                                ? "亨利" : "艾琳")
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_ACTIVE);
    }

    private record Fixture(
            TrpgChildSceneCommandService service,
            GroupChatReplyStepMapper stepMapper,
            GroupChatToolCallMapper toolCallMapper,
            GroupReplyPlanMapper planMapper,
            GroupReplyPlanItemMapper itemMapper,
            GroupConversation conversation,
            GroupChatReplyStep step,
            GroupChatTurn turn,
            GroupReplyPlan parent,
            List<GroupReplyPlanItem> investigatorItems,
            TrpgSceneProgressStore progressStore) {
    }
}
