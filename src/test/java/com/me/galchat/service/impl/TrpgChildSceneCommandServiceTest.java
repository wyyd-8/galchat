package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgChildSceneCommandServiceTest {

    @Test
    void allowsAllActiveInvestigatorsToEnterDescribedDescendant() {
        Fixture fixture = fixture();

        String result = fixture.service().startChildScene(
                7L, 51L, "阁楼", List.of("亨利", "艾琳"));

        assertThat(result).isEqualTo(
                "已创建子场景“阁楼”，调查员亨利、艾琳将进入该场景。");
        verify(fixture.planService()).startChildUnderLock(
                fixture.conversation(),
                fixture.parent(),
                fixture.attic(),
                fixture.investigatorItems());
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

    private Fixture fixture() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper =
                mock(GroupChatTurnMapper.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        TrpgChildScenePlanService planService =
                mock(TrpgChildScenePlanService.class);
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
        when(stepMapper.selectById(51L)).thenReturn(
                new GroupChatReplyStep().setId(51L).setTurnId(61L)
                        .setSpeakerType(GroupChatConstant.ACTOR_KP));
        when(turnMapper.selectById(61L)).thenReturn(
                new GroupChatTurn().setId(61L)
                        .setConversationId(7L).setPlanId(31L)
                        .setPlanSource(
                                GroupChatConstant.PLAN_SOURCE_SCENE));
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
                        participantService, planService);
        return new Fixture(
                service, planService, itemMapper, conversation,
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
            TrpgChildScenePlanService planService,
            GroupReplyPlanItemMapper itemMapper,
            GroupConversation conversation,
            GroupReplyPlan parent,
            CocModuleLocation attic,
            List<GroupReplyPlanItem> investigatorItems) {
    }
}
