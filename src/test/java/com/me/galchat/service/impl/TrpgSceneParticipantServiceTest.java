package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
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
        TrpgRuntimeChildSceneMapper runtimeSceneMapper =
                mock(TrpgRuntimeChildSceneMapper.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setModuleId(5L)
                .setActiveReplyPlanId(31L);
        when(planMapper.selectById(31L)).thenReturn(
                new GroupReplyPlan()
                        .setId(31L)
                        .setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(21L)
                        .setParentPlanId(20L));
        when(planMapper.selectById(20L)).thenReturn(
                new GroupReplyPlan()
                        .setId(20L)
                        .setConversationId(7L)
                        .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setContextId(21L));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item(GroupChatConstant.ACTOR_USER, 101L,
                        GroupChatConstant.PARTICIPANT_ACTIVE),
                item(GroupChatConstant.ACTOR_CHARACTER, 9L,
                        GroupChatConstant.PARTICIPANT_WAITING),
                item(GroupChatConstant.ACTOR_CHARACTER, 10L,
                        GroupChatConstant.PARTICIPANT_READY),
                item(GroupChatConstant.ACTOR_KP, null, null)));
        when(locationMapper.selectById(21L)).thenReturn(
                new CocModuleLocation().setId(21L)
                        .setModuleId(5L).setName("森林"));
        when(runtimeSceneMapper.selectById(31L)).thenReturn(
                new TrpgRuntimeChildScene()
                        .setPlanId(31L)
                        .setConversationId(7L)
                        .setSceneName("临时藏身处"));
        TrpgSceneParticipantService service =
                new TrpgSceneParticipantService(
                        planMapper, itemMapper, locationMapper,
                        runtimeSceneMapper);

        TrpgSceneParticipantService.SceneState state =
                service.state(conversation);

        assertThat(state.scenePath())
                .isEqualTo("森林 - 临时藏身处");
        assertThat(state.activeInvestigatorNames())
                .containsExactly("亨利");
        assertThat(state.activeInvestigatorCharacterIds())
                .containsExactly(101L);
        assertThat(state.waitingInvestigatorNames())
                .containsExactly("艾琳");
    }

    private GroupReplyPlanItem item(
            String actorType, Long actorId, String status) {
        return new GroupReplyPlanItem()
                .setPlanId(31L)
                .setActorType(actorType)
                .setActorId(actorId)
                .setSubjectCharacterId(
                        GroupChatConstant.ACTOR_USER.equals(actorType)
                                ? actorId
                                : GroupChatConstant.ACTOR_CHARACTER.equals(
                                actorType) ? actorId + 100L : null)
                .setSubjectCharacterName(
                        actorId == null ? null : switch (actorId.intValue()) {
                            case 101 -> "亨利";
                            case 9 -> "艾琳";
                            case 10 -> "威廉";
                            default -> null;
                        })
                .setParticipantStatus(status);
    }
}
