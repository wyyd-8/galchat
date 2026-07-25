package com.me.galchat.groupchat.context;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTopic;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTopicMapper;
import com.me.galchat.vector.GroupTopicVectorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupTopicServiceTest {

    @Test
    void createsTheFirstSharedTopicAtTheUserMessageSequence() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = conversation();
        GroupChatMessage userMessage = userMessage(100L, 20L, "开始聊天");
        when(fixture.topicMapper.selectList(any())).thenReturn(List.of());

        fixture.service.onTurnStarted(conversation, userMessage);

        verify(fixture.topicMapper).insert(org.mockito.ArgumentMatchers.argThat((GroupChatTopic topic) ->
                topic.getConversationId().equals(7L)
                        && topic.getStartSequence().equals(20L)
                        && GroupChatConstant.TOPIC_BOUNDARY_SEMANTIC.equals(topic.getBoundaryReason())));
        verify(fixture.vectorService, never()).addTopic(any(), any(), any());
    }

    @Test
    void semanticSwitchArchivesOnlyTheTopicLeavingTheTwoTopicWindow() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = conversation();
        GroupChatTopic current = topic(2L, 10L);
        GroupChatTopic previous = topic(1L, 1L);
        GroupChatMessage existing = userMessage(90L, 10L, "讨论晚餐");
        GroupChatMessage userMessage = userMessage(100L, 20L, "换个话题，聊旅行");
        when(fixture.topicMapper.selectList(any())).thenReturn(List.of(current, previous));
        when(fixture.messageMapper.selectList(any())).thenReturn(List.of(existing));
        when(fixture.classifier.isSameTopic(List.of(existing), userMessage)).thenReturn(false);

        fixture.service.onTurnStarted(conversation, userMessage);

        verify(fixture.vectorService).addTopic(conversation, previous, 10L);
        verify(fixture.topicMapper).insert(org.mockito.ArgumentMatchers.argThat((GroupChatTopic topic) ->
                topic.getStartSequence().equals(20L)));
    }

    @Test
    void sameTopicKeepsBoundaryAndWindowStartsAtPreviousTopic() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = conversation();
        GroupChatTopic current = topic(2L, 10L);
        GroupChatTopic previous = topic(1L, 1L);
        GroupChatMessage existing = userMessage(90L, 10L, "讨论晚餐");
        GroupChatMessage userMessage = userMessage(100L, 20L, "那就吃面吧");
        when(fixture.topicMapper.selectList(any())).thenReturn(List.of(current, previous));
        when(fixture.messageMapper.selectList(any())).thenReturn(List.of(existing));
        when(fixture.classifier.isSameTopic(List.of(existing), userMessage)).thenReturn(true);

        fixture.service.onTurnStarted(conversation, userMessage);

        verify(fixture.topicMapper, never()).insert(any(GroupChatTopic.class));
        verify(fixture.vectorService, never()).addTopic(any(), any(), any());
        assertThat(fixture.service.windowStartSequence(conversation)).isEqualTo(1L);
    }

    @Test
    void withdrawingNewestBoundaryDeletesTheTopicReenteringTheTwoTopicWindow() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = conversation();
        when(fixture.topicMapper.selectList(any())).thenReturn(List.of(
                topic(6L, 60L),
                topic(5L, 50L),
                topic(4L, 40L),
                topic(3L, 30L),
                topic(2L, 20L)));

        fixture.service.rollbackTurnBoundary(conversation, 60L);

        verify(fixture.topicMapper).deleteById(6L);
        verify(fixture.vectorService).deleteTopic(7L, 40L, 50L);
    }

    private GroupConversation conversation() {
        return new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L)
                .setWorldId(4L)
                .setMode(GroupChatConstant.MODE_CHAT);
    }

    private GroupChatTopic topic(Long id, Long startSequence) {
        return new GroupChatTopic()
                .setId(id)
                .setConversationId(7L)
                .setStartSequence(startSequence);
    }

    private GroupChatMessage userMessage(Long id, Long sequence, String content) {
        return new GroupChatMessage()
                .setId(id)
                .setConversationId(7L)
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setVisibility("public")
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setSequenceNo(sequence)
                .setContent(content);
    }

    private static class Fixture {
        private final GroupChatTopicMapper topicMapper = mock(GroupChatTopicMapper.class);
        private final GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        private final GroupTopicClassifier classifier = mock(GroupTopicClassifier.class);
        private final GroupTopicVectorService vectorService = mock(GroupTopicVectorService.class);
        private final GroupTopicService service =
                new GroupTopicService(topicMapper, messageMapper, classifier, vectorService);
    }
}
