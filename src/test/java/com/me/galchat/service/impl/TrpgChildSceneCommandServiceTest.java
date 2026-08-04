package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
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
                7L, 51L, "阁楼", List.of("亨利", "艾琳"));

        assertThat(result).isEqualTo(
                "已创建子场景“阁楼”，调查员亨利、艾琳将进入该场景。");
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
                                {"childSceneName":"阁楼","investigatorNames":["亨利","艾琳"]}
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
    void rejectsSecondValidChildSceneRequestFromTheSameKpStep() {
        Fixture fixture = fixture();
        when(fixture.toolCallMapper().selectList(any()))
                .thenReturn(List.of(new GroupChatToolCall()
                        .setReplyStepId(fixture.step().getId())
                        .setToolName("startChildScene")
                        .setToolArguments("""
                                {"childSceneName":"阁楼","investigatorNames":["亨利"]}
                                """)
                        .setToolResult("已创建子场景")));

        assertThatThrownBy(() -> fixture.service().startChildScene(
                7L, 51L, "阁楼", List.of("艾琳")))
                .hasMessageContaining("同一回复步骤")
                .hasMessageContaining("子场景");
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
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        TrpgChildScenePlanService planService =
                new TrpgChildScenePlanService(
                        planMapper, itemMapper, conversationMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(5L)
                .setActiveReplyPlanId(31L)
                .setMode(GroupChatConstant.MODE_TRPG);
        GroupReplyPlan parent = new GroupReplyPlan()
                .setId(31L).setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(21L);
        CocModuleLocation root = new CocModuleLocation()
                .setId(21L).setModuleId(5L).setName("教堂")
                .setContent("教堂内容");
        CocModuleLocation attic = new CocModuleLocation()
                .setId(22L).setModuleId(5L)
                .setParentLocationId(21L).setName("阁楼")
                .setContent("阁楼内容");
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
        when(locationMapper.selectList(any()))
                .thenReturn(List.of(root, attic));
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
                        planMapper, itemMapper, locationMapper,
                        participantService, planService,
                        toolCallMapper, new ObjectMapper());
        return new Fixture(
                service, stepMapper, toolCallMapper, planMapper,
                itemMapper, conversation, step, turn,
                parent, attic, investigatorItems);
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
            CocModuleLocation attic,
            List<GroupReplyPlanItem> investigatorItems) {
    }
}
