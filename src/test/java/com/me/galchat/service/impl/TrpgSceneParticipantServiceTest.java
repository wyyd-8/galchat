package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgSceneParticipantServiceTest {

    @Test
    void resolvesScenePathAndSeparatesActiveFromWaiting() {
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        GroupReplyPlanItemMapper itemMapper =
                mock(GroupReplyPlanItemMapper.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgParticipantService investigatorService =
                mock(TrpgParticipantService.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setModuleId(5L)
                .setActiveReplyPlanId(31L);
        when(planMapper.selectById(31L)).thenReturn(
                new GroupReplyPlan()
                        .setId(31L)
                        .setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(22L));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item(GroupChatConstant.ACTOR_USER, 101L,
                        GroupChatConstant.PARTICIPANT_ACTIVE),
                item(GroupChatConstant.ACTOR_CHARACTER, 9L,
                        GroupChatConstant.PARTICIPANT_WAITING),
                item(GroupChatConstant.ACTOR_KP, null, null)));
        when(locationMapper.selectList(any())).thenReturn(List.of(
                new CocModuleLocation().setId(21L)
                        .setModuleId(5L).setName("摩根老大的住宅"),
                new CocModuleLocation().setId(22L)
                        .setModuleId(5L).setParentLocationId(21L)
                        .setName("书房")));
        when(investigatorService.listInvestigators(conversation))
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
        TrpgSceneParticipantService service =
                new TrpgSceneParticipantService(
                        planMapper, itemMapper, locationMapper,
                        investigatorService);

        TrpgSceneParticipantService.SceneState state =
                service.state(conversation);

        assertThat(state.scenePath())
                .isEqualTo("摩根老大的住宅 - 书房");
        assertThat(state.activeInvestigatorNames())
                .containsExactly("亨利");
        assertThat(state.waitingInvestigatorNames())
                .containsExactly("艾琳");
    }

    private GroupReplyPlanItem item(
            String actorType, Long actorId, String status) {
        return new GroupReplyPlanItem()
                .setPlanId(31L)
                .setActorType(actorType)
                .setActorId(actorId)
                .setParticipantStatus(status);
    }
}
