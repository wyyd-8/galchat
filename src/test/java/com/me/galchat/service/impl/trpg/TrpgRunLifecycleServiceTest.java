package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.impl.group.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrpgRunLifecycleServiceTest {
    @Test
    void onlyKpRecordsRequestWithoutChangingCurrentTurn() {
        var conversations = mock(GroupConversationService.class);
        var steps = mock(GroupChatReplyStepMapper.class);
        var turns = mock(GroupChatTurnMapper.class);
        var recovery = mock(GroupTurnRecoveryService.class);
        var mapper = mock(TrpgCompletionMapper.class);
        var completion = mock(TrpgCompletionService.class);
        var service = new TrpgRunLifecycleService(conversations, steps, turns, mapper);
        var conversation = new GroupConversation().setId(7L).setActiveReplyPlanId(20L).setMode(GroupChatConstant.MODE_TRPG).setStatus("active");
        var step = new GroupChatReplyStep().setId(8L).setTurnId(9L).setSpeakerType("user").setStatus("running").setActionType(GroupChatConstant.ACTION_TRPG_SCENE);
        when(conversations.requireActive(7L)).thenReturn(conversation);
        when(steps.selectById(8L)).thenReturn(step);
        when(turns.selectById(9L)).thenReturn(new GroupChatTurn().setId(9L).setPlanId(20L).setConversationId(7L).setStatus("running").setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE));
        assertThatThrownBy(() -> service.requestFinish(7L, 8L)).hasMessageContaining("只有KP");
        verifyNoInteractions(mapper);
        step.setSpeakerType(GroupChatConstant.ACTOR_KP);
        service.requestFinish(7L, 8L);
        var saved = ArgumentCaptor.forClass(TrpgCompletion.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getTurnId()).isEqualTo(9L);
        verifyNoInteractions(recovery);
        when(mapper.selectById(7L)).thenReturn(saved.getValue());
        service.requestFinish(7L, 8L);
        verify(mapper, times(1)).insert(any(TrpgCompletion.class));
        assertThat(service.hasFinishRequest(conversation, 9L)).isFalse();
        when(steps.countCompletedRunFinishByTurn(9L)).thenReturn(1L);
        assertThat(service.hasFinishRequest(conversation, 9L)).isTrue();
        assertThat(service.hasFinishRequest(conversation, 10L)).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "SCENE, trpg_scene_action, true", "POST_COMBAT, trpg_post_combat_transition, true",
        "SCENE_SELECTION, trpg_scene_selection, false", "COMBAT, combat_adjudicate, false",
        "COMBAT, trpg_scene_action, false", "SCENE, trpg_scene_intro, false"
    })
    void finishIsAllowedOnlyForExplorationAndPostCombat(String source, String action, boolean allowed) {
        var conversations = mock(GroupConversationService.class);
        var steps = mock(GroupChatReplyStepMapper.class);
        var turns = mock(GroupChatTurnMapper.class);
        var mapper = mock(TrpgCompletionMapper.class);
        var service = new TrpgRunLifecycleService(conversations, steps, turns, mapper);
        when(conversations.requireActive(7L)).thenReturn(new GroupConversation().setId(7L).setActiveReplyPlanId(20L).setMode("trpg"));
        when(steps.selectById(8L)).thenReturn(new GroupChatReplyStep().setId(8L).setTurnId(9L)
                .setSpeakerType("kp").setActionType(action).setStatus("running"));
        when(turns.selectById(9L)).thenReturn(new GroupChatTurn().setId(9L).setPlanId(20L).setConversationId(7L)
                .setPlanSource(source).setStatus("running"));
        if (allowed) {
            service.requestFinish(7L, 8L);
            verify(mapper).insert(any(TrpgCompletion.class));
        } else {
            assertThatThrownBy(() -> service.requestFinish(7L, 8L)).hasMessageContaining("探索或战斗结束后");
            verifyNoInteractions(mapper);
        }
    }

}
