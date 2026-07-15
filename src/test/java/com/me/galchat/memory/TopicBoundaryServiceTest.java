package com.me.galchat.memory;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.vector.ChatHistoryVectorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void clearBoundaryIfReferencesRollsCurrentBoundaryBackWhenOnlyCurrentStartIsWithdrawn() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TopicBoundaryService topicBoundaryService = new TopicBoundaryService(redisTemplate, mock(ChatClient.class),
                mock(UserChatMemory.class), mock(ChatHistoryVectorService.class));

        String boundaryKey = RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + "1:2";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(boundaryKey)).thenReturn("""
                {
                  "%s": 8,
                  "%s": 10,
                  "%s": 18
                }
                """.formatted(ChatConstant.TOPIC_PREVIOUS_START_ID_KEY,
                ChatConstant.TOPIC_CURRENT_START_ID_KEY,
                ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY));

        topicBoundaryService.clearBoundaryIfReferences(1L, 2L, Set.of(10L, 11L));

        verify(redisTemplate, never()).delete(boundaryKey);
        verify(valueOperations).set(eq(boundaryKey), argThat(value -> {
            try {
                org.json.JSONObject jsonObject = new org.json.JSONObject(value);
                return jsonObject.isNull(ChatConstant.TOPIC_PREVIOUS_START_ID_KEY)
                        && jsonObject.getLong(ChatConstant.TOPIC_CURRENT_START_ID_KEY) == 8L
                        && jsonObject.getLong(ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY) == 8L;
            } catch (org.json.JSONException e) {
                return false;
            }
        }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearBoundaryIfReferencesIgnoresLastCheckedMessageId() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TopicBoundaryService topicBoundaryService = new TopicBoundaryService(redisTemplate, mock(ChatClient.class),
                mock(UserChatMemory.class), mock(ChatHistoryVectorService.class));

        String boundaryKey = RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + "1:2";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(boundaryKey)).thenReturn("""
                {
                  "%s": 8,
                  "%s": 10,
                  "%s": 18
                }
                """.formatted(ChatConstant.TOPIC_PREVIOUS_START_ID_KEY,
                ChatConstant.TOPIC_CURRENT_START_ID_KEY,
                ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY));

        topicBoundaryService.clearBoundaryIfReferences(1L, 2L, Set.of(18L));

        verify(redisTemplate, never()).delete(boundaryKey);
        verify(valueOperations, never()).set(eq(boundaryKey), org.mockito.ArgumentMatchers.anyString());
    }
}
