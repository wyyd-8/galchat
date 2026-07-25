package com.me.galchat.vector;

import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTopic;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTopicMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.memory.TopicBoundary;
import com.me.galchat.memory.TopicBoundaryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecentChatMemoryServiceTest {

    @Test
    void returnsOnlyTheNewestThreeSingleChatTopicsAsRawCandidates() {
        UserChatHistoryMapper historyMapper = mock(UserChatHistoryMapper.class);
        TopicBoundaryService boundaryService = mock(TopicBoundaryService.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupChatTopicMapper topicMapper = mock(GroupChatTopicMapper.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        RecentChatMemoryService service = new RecentChatMemoryService(
                historyMapper, boundaryService, conversationMapper, topicMapper, messageMapper);

        when(boundaryService.getBoundary(any())).thenReturn(
                new TopicBoundary(List.of(10L, 20L, 30L, 40L), 40L));
        when(historyMapper.selectList(any())).thenReturn(List.of(
                singleMessage(10L, null, "archived"),
                singleMessage(20L, null, "topic two user"),
                singleMessage(25L, "assistant", "topic two assistant"),
                singleMessage(30L, null, "topic three"),
                singleMessage(40L, null, "topic four user"),
                singleMessage(45L, "assistant", "topic four assistant")));
        when(conversationMapper.selectActiveChatByCharacter(1L, 2L)).thenReturn(List.of());

        List<Document> documents = service.queryRecentMemories(1L, 2L);

        assertThat(documents).hasSize(3);
        assertThat(documents).allSatisfy(document ->
                assertThat(document.getMetadata())
                        .containsEntry(VectorConstant.SOURCE_METADATA_KEY, VectorConstant.CHAT_HISTORY_SOURCE));
        assertThat(documents.get(0).getText())
                .contains("topic two user", "topic two assistant")
                .doesNotContain("archived", "topic three");
        assertThat(documents.get(1).getText())
                .contains("topic three")
                .doesNotContain("topic two user", "topic four user");
        assertThat(documents.get(2).getText())
                .contains("topic four user", "topic four assistant")
                .doesNotContain("topic three");
        assertThat(documents.get(0).getMetadata())
                .containsEntry(VectorConstant.START_MESSAGE_ID_METADATA_KEY, 20L)
                .containsEntry(VectorConstant.END_MESSAGE_ID_METADATA_KEY, 30L);
        assertThat(documents.get(2).getMetadata())
                .containsEntry(VectorConstant.START_MESSAGE_ID_METADATA_KEY, 40L)
                .containsEntry(VectorConstant.END_MESSAGE_ID_METADATA_KEY, 46L);
    }

    @Test
    void returnsOnlyTheNewestTwoTopicsFromVisibleActiveOrdinaryGroups() {
        UserChatHistoryMapper historyMapper = mock(UserChatHistoryMapper.class);
        TopicBoundaryService boundaryService = mock(TopicBoundaryService.class);
        GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        GroupChatTopicMapper topicMapper = mock(GroupChatTopicMapper.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        RecentChatMemoryService service = new RecentChatMemoryService(
                historyMapper, boundaryService, conversationMapper, topicMapper, messageMapper);

        when(boundaryService.getBoundary(any())).thenReturn(new TopicBoundary(List.of(), null));
        GroupConversation conversation = new GroupConversation()
                .setId(100L)
                .setUserWorldId(1L);
        when(conversationMapper.selectActiveChatByCharacter(1L, 2L)).thenReturn(List.of(conversation));
        when(topicMapper.selectList(any())).thenReturn(List.of(
                new GroupChatTopic().setId(3L).setConversationId(100L).setStartSequence(30L),
                new GroupChatTopic().setId(2L).setConversationId(100L).setStartSequence(20L),
                new GroupChatTopic().setId(1L).setConversationId(100L).setStartSequence(10L)));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                groupMessage(10L, "archived group topic"),
                groupMessage(20L, "recent group topic"),
                groupMessage(25L, "recent group reply"),
                groupMessage(30L, "current group topic"),
                groupMessage(35L, "current group reply")));

        List<Document> documents = service.queryRecentMemories(1L, 2L);

        assertThat(documents).hasSize(2);
        assertThat(documents).allSatisfy(document ->
                assertThat(document.getMetadata())
                        .containsEntry(VectorConstant.SOURCE_METADATA_KEY, VectorConstant.GROUP_TOPIC_SOURCE)
                        .containsEntry(VectorConstant.CONVERSATION_ID_METADATA_KEY, 100L));
        assertThat(documents.get(0).getText())
                .contains("recent group topic", "recent group reply")
                .doesNotContain("archived group topic", "current group topic");
        assertThat(documents.get(1).getText())
                .contains("current group topic", "current group reply")
                .doesNotContain("recent group topic");
        assertThat(documents.get(0).getMetadata())
                .containsEntry(VectorConstant.START_SEQUENCE_METADATA_KEY, 20L)
                .containsEntry(VectorConstant.END_SEQUENCE_METADATA_KEY, 30L);
        assertThat(documents.get(1).getMetadata())
                .containsEntry(VectorConstant.START_SEQUENCE_METADATA_KEY, 30L)
                .containsEntry(VectorConstant.END_SEQUENCE_METADATA_KEY, 36L);
    }

    private UserChatHistory singleMessage(Long id, String type, String content) {
        return new UserChatHistory()
                .setId(id)
                .setUserWorldId(1L)
                .setCharacterId(2L)
                .setType(type)
                .setContent(content)
                .setTimestamp(LocalDateTime.of(2026, 7, 25, 10, 0).plusMinutes(id));
    }

    private GroupChatMessage groupMessage(Long sequence, String content) {
        return new GroupChatMessage()
                .setId(sequence)
                .setConversationId(100L)
                .setSpeakerType(sequence % 2 == 0 ? "user" : "character")
                .setSpeakerId(sequence % 2 == 0 ? null : 2L)
                .setSequenceNo(sequence)
                .setContent(content)
                .setCreatedAt(LocalDateTime.of(2026, 7, 25, 12, 0).plusMinutes(sequence));
    }
}
