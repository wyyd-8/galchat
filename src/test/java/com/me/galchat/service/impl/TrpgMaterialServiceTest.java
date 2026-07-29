package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleMaterial;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.CocModuleMaterialMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgMaterialServiceTest {

    @Test
    void showMaterialPersistsRecoverableMaterialMessageByExactName() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        CocModuleMaterialMapper materialMapper =
                mock(CocModuleMaterialMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        TrpgMaterialStateStore stateStore =
                mock(TrpgMaterialStateStore.class);
        TrpgMaterialService service = new TrpgMaterialService(
                conversationService, materialMapper, stepMapper,
                turnMapper, messageMapper, stateStore,
                JsonMapper.builder().build());
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        when(conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(materialMapper.selectList(any())).thenReturn(List.of(
                new CocModuleMaterial()
                        .setId(31L).setModuleId(3L)
                        .setTitle("玛德琳的信")
                        .setDescription("信中提到酒店")
                        .setImageUrl("https://oss.example/material.jpg")));
        when(stepMapper.selectById(41L)).thenReturn(
                new GroupChatReplyStep().setId(41L).setTurnId(51L)
                        .setSpeakerType(GroupChatConstant.ACTOR_KP));
        when(turnMapper.selectById(51L)).thenReturn(
                new GroupChatTurn().setId(51L).setConversationId(7L)
                        .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                        .setPlanContextId(21L));
        when(conversationService.nextSequence(7L)).thenReturn(4L);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<GroupChatMessage>getArgument(0).setId(61L);
            return 1;
        }).when(messageMapper).insert(any(GroupChatMessage.class));

        var result = service.showMaterial(
                7L, 41L, " 玛德琳的信 ");

        assertThat(result.shown()).isTrue();
        assertThat(result.message().getSceneId()).isEqualTo(21L);
        assertThat(result.message().getMessageKind())
                .isEqualTo(GroupChatConstant.MESSAGE_MATERIAL);
        assertThat(result.message().getContent())
                .contains("\"schemaVersion\":1")
                .contains("\"materialId\":31")
                .contains("\"title\":\"玛德琳的信\"")
                .contains("\"imageUrl\":\"https://oss.example/material.jpg\"");
        verify(stateStore).markShown(7L, 31L);
    }
}
