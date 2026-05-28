package com.me.galchat.memory;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.tool.VectorTools;
import com.me.galchat.vector.ChatHistoryVectorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class TopicBoundaryServiceTest {
    @Autowired
    ChatHistoryVectorService chatHistoryVectorService;

    @Test
    void test() {
        chatHistoryVectorService.addChatHistory(3L, 1L, 93L, 110L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void endStoryTopicVectorizesActiveStoryWindowBeforeClearingBoundary() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ChatHistoryVectorService chatHistoryVectorService = mock(ChatHistoryVectorService.class);
        TopicBoundaryService topicBoundaryService = new TopicBoundaryService(redisTemplate, mock(ChatClient.class),
                mock(UserChatMemory.class), mock(VectorTools.class), chatHistoryVectorService);

        String activeStoryKey = RedisConstant.STORY_ACTIVE_KEY_PREFIX + "1:2";
        String boundaryKey = RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + "1:2";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeStoryKey)).thenReturn("99:10");

        topicBoundaryService.endStoryTopic(1L, 2L, 20L);

        verify(chatHistoryVectorService).addChatHistory(1L, 2L, 10L, 21L);
        verify(redisTemplate).delete(activeStoryKey);
        verify(redisTemplate).delete(boundaryKey);
    }

    @Test
    @SuppressWarnings("unchecked")
    void endStoryTopicFallsBackToSavedBoundaryWhenActiveStoryKeyIsMissing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ChatHistoryVectorService chatHistoryVectorService = mock(ChatHistoryVectorService.class);
        TopicBoundaryService topicBoundaryService = new TopicBoundaryService(redisTemplate, mock(ChatClient.class),
                mock(UserChatMemory.class), mock(VectorTools.class), chatHistoryVectorService);

        String activeStoryKey = RedisConstant.STORY_ACTIVE_KEY_PREFIX + "1:2";
        String boundaryKey = RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + "1:2";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(activeStoryKey)).thenReturn(null);
        when(valueOperations.get(boundaryKey)).thenReturn("""
                {
                  "%s": null,
                  "%s": 10,
                  "%s": 18
                }
                """.formatted(ChatConstant.TOPIC_PREVIOUS_START_ID_KEY,
                ChatConstant.TOPIC_CURRENT_START_ID_KEY,
                ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY));

        topicBoundaryService.endStoryTopic(1L, 2L, 20L);

        verify(chatHistoryVectorService).addChatHistory(1L, 2L, 10L, 21L);
        verify(redisTemplate).delete(activeStoryKey);
        verify(redisTemplate).delete(boundaryKey);
    }
}
