package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActorRef;
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

        String result = fixture.service().startChildScene(
                7L, 51L, "临时藏身处", List.of("亨利", "艾琳"));

        assertThat(result).isEqualTo(
                "已创建子场景“临时藏身处”，调查员亨利、艾琳将进入该场景。");
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

        String result = fixture.service()
                .resumeWaitingInvestigators(
                        7L, 51L, List.of("艾琳"));

        assertThat(result)
                .isEqualTo("艾琳已结束等待，将从下一轮开始正常参与行动。");
        assertThat(fixture.investigatorItems().get(1)
                .getParticipantStatus())
                .isEqualTo(GroupChatConstant.PARTICIPANT_ACTIVE);
        verify(fixture.itemMapper()).updateById(
                fixture.investigatorItems().get(1));
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
                        .setToolResult("已创建子场景")));
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
                                .setToolResult("已创建子场景"),
                        new GroupChatToolCall()
                                .setReplyStepId(fixture.step().getId())
                                .setToolName("startChildScene")
                                .setToolArguments("""
                                        {"childSceneName":"地下室","investigatorNames":["艾琳"]}
                                        """)
                                .setToolResult("已创建子场景")));
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
                        .setToolResult("已创建子场景")));

        String result = fixture.service().startChildScene(
                7L, 51L, "另一条林间小路", List.of("艾琳"));

        assertThat(result).isEqualTo(
                "已创建子场景“另一条林间小路”，调查员艾琳将进入该场景。");
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
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        TrpgChildScenePlanService planService =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper,
                        mock(TrpgRuntimeChildSceneMapper.class));
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
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(
                        new TrpgParticipantService.Participant(
                                new GroupActorRef(
                                        GroupChatConstant.ACTOR_USER,
                                        101L),
                                101L, "亨利", "用户"),
                        new TrpgParticipantService.Participant(
                                new GroupActorRef(
                                        GroupChatConstant.ACTOR_CHARACTER,
                                        9L),
                                102L, "艾琳", "代理")));
        TrpgChildSceneCommandService service =
                new TrpgChildSceneCommandService(
                        conversationService, stepMapper, turnMapper,
                        planMapper, itemMapper,
                        participantService, planService,
                        toolCallMapper, new ObjectMapper());
        return new Fixture(
                service, stepMapper, toolCallMapper, planMapper,
                itemMapper, conversation, step, turn,
                parent, investigatorItems);
    }

    private GroupReplyPlanItem item(
            String actorType, Long actorId, int order) {
        return new GroupReplyPlanItem()
                .setId((long) order)
                .setPlanId(31L)
                .setGroupKey("scene:21")
                .setGroupName("教堂")
                .setGroupOrder(1)
                .setItemOrder(order)
                .setActorType(actorType)
                .setActorId(actorId)
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
            List<GroupReplyPlanItem> investigatorItems) {
    }
}
