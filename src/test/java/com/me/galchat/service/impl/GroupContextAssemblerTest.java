package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.groupchat.tool.GroupToolHistoryAssembler;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.service.IUserCharacterInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupContextAssemblerTest {

    @Test
    void mapsOnlyCurrentCharactersHistoryToAssistantAndKeepsOtherSpeakerIdentity() {
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupToolHistoryAssembler toolHistoryAssembler = mock(GroupToolHistoryAssembler.class);
        GroupContextAssembler assembler = new GroupContextAssembler(messageMapper, conversationService,
                chatService, characterService, toolHistoryAssembler);

        GroupConversation conversation = new GroupConversation().setId(8L).setUserWorldId(1L).setWorldId(2L);
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L).setCharacterName("Alice"),
                new UserCharacterInfo().setCharacterId(12L).setCharacterName("Bob")));
        when(chatService.buildSystemPrompt(2L, 1L, 11L)).thenReturn("包含用户信息的角色基础提示词");
        when(conversationService.listMembers(8L)).thenReturn(List.of(
                new GroupChatMember().setActorId(11L), new GroupChatMember().setActorId(12L)));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(GroupChatConstant.ACTOR_USER, null, "进入房间"),
                message(GroupChatConstant.ACTOR_CHARACTER, 12L, "我检查门口"),
                message(GroupChatConstant.ACTOR_CHARACTER, 11L, "我打开手电").setReplyStepId(41L)));
        when(toolHistoryAssembler.beforeMessages(any(), org.mockito.ArgumentMatchers.eq(11L)))
                .thenReturn(Map.of(41L, List.of(new UserMessage("<dice-roll summary-id=\"501\" />"))));

        List<Message> prompt = assembler.assembleContextFrom(conversation, 11L, 1L);

        assertThat(prompt.stream().filter(AssistantMessage.class::isInstance).map(Message::getText))
                .containsExactly("我打开手电");
        assertThat(prompt.stream().filter(UserMessage.class::isInstance).map(Message::getText))
                .anyMatch(text -> text.contains("speaker=\"Bob\"") && text.contains("我检查门口"));
        assertThat(prompt).extracting(Message::getText)
                .endsWith("<dice-roll summary-id=\"501\" />", "我打开手电");
        assertThat(assembler.baseSystemPrompt(conversation, 11L))
                .contains("包含用户信息的角色基础提示词", "Alice", "Bob");
        verify(chatService).buildSystemPrompt(2L, 1L, 11L);
    }

    @Test
    void assemblerHasNoThinkingPersistenceDependency() {
        assertThat(Arrays.stream(GroupContextAssembler.class.getDeclaredFields()).map(Field::getName))
                .noneMatch(name -> name.toLowerCase().contains("thinking"));
    }

    private GroupChatMessage message(String speakerType, Long speakerId, String content) {
        return new GroupChatMessage()
                .setSpeakerType(speakerType)
                .setSpeakerId(speakerId)
                .setContent(content)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
    }
}
