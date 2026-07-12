package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatThinkingMapper;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupContextAssemblerTest {

    @Test
    void mapsOnlyCurrentCharactersHistoryToAssistantAndKeepsOtherSpeakerIdentity() {
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupConversationService conversationService = mock(GroupConversationService.class);
        GroupContextCompactionService compactionService = mock(GroupContextCompactionService.class);
        IUserWorldPrefixService worldService = mock(IUserWorldPrefixService.class);
        IUserCharacterInfoService characterService = mock(IUserCharacterInfoService.class);
        GroupContextAssembler assembler = new GroupContextAssembler(messageMapper, conversationService,
                compactionService, worldService, characterService);

        GroupConversation conversation = new GroupConversation().setId(8L).setUserWorldId(1L).setWorldId(2L);
        when(characterService.listByUserWorldId(1L)).thenReturn(List.of(
                new UserCharacterInfo().setCharacterId(11L).setCharacterName("Alice"),
                new UserCharacterInfo().setCharacterId(12L).setCharacterName("Bob")));
        when(characterService.buildCharacterPrompt(1L, 11L)).thenReturn("Alice角色卡");
        when(worldService.buildWorldPrompt(2L)).thenReturn("世界设定");
        when(conversationService.listMembers(8L)).thenReturn(List.of(
                new GroupChatMember().setActorId(11L), new GroupChatMember().setActorId(12L)));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(GroupChatConstant.ACTOR_USER, null, "进入房间"),
                message(GroupChatConstant.ACTOR_CHARACTER, 12L, "我检查门口"),
                message(GroupChatConstant.ACTOR_CHARACTER, 11L, "我打开手电")));

        List<Message> prompt = assembler.assemble(conversation, 11L);

        assertThat(prompt.stream().filter(AssistantMessage.class::isInstance).map(Message::getText))
                .containsExactly("我打开手电");
        assertThat(prompt.stream().filter(UserMessage.class::isInstance).map(Message::getText))
                .anyMatch(text -> text.contains("speaker=\"Bob\"") && text.contains("我检查门口"));
    }

    @Test
    void assemblerHasNoThinkingPersistenceDependency() {
        assertThat(Arrays.stream(GroupContextAssembler.class.getDeclaredFields()).map(Field::getType))
                .doesNotContain(GroupChatThinkingMapper.class);
    }

    private GroupChatMessage message(String speakerType, Long speakerId, String content) {
        return new GroupChatMessage()
                .setSpeakerType(speakerType)
                .setSpeakerId(speakerId)
                .setContent(content)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
    }
}
