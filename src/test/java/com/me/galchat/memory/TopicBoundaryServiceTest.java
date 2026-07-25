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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void rollbackRemovesNewestBoundaryAndDeletesTheTopicReenteringTheHotWindow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ChatHistoryVectorService vectorService = mock(ChatHistoryVectorService.class);
        TopicBoundaryService topicBoundaryService = new TopicBoundaryService(redisTemplate, mock(ChatClient.class),
                mock(UserChatMemory.class), vectorService);

        String boundaryKey = RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + "1:2";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(boundaryKey)).thenReturn("""
                {
                  "%s": [1, 2, 3, 4, 5, 10],
                  "%s": 18
                }
                """.formatted(ChatConstant.TOPIC_START_IDS_KEY,
                ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY));

        topicBoundaryService.rollbackAfterWithdraw(1L, 2L, Set.of(10L, 11L), 9L);

        verify(valueOperations).set(eq(boundaryKey), argThat(value -> {
            try {
                org.json.JSONObject jsonObject = new org.json.JSONObject(value);
                return jsonObject.getJSONArray(ChatConstant.TOPIC_START_IDS_KEY).toString()
                        .equals("[1,2,3,4,5]")
                        && jsonObject.getLong(ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY) == 9L;
            } catch (org.json.JSONException e) {
                return false;
            }
        }));
        verify(vectorService).deleteChatHistory(1L, 2L, 3L, 4L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollbackWithoutBoundaryOnlyMovesLastCheckedMessage() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ChatHistoryVectorService vectorService = mock(ChatHistoryVectorService.class);
        TopicBoundaryService topicBoundaryService = new TopicBoundaryService(redisTemplate, mock(ChatClient.class),
                mock(UserChatMemory.class), vectorService);

        String boundaryKey = RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + "1:2";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(boundaryKey)).thenReturn("""
                {
                  "%s": [8, 10],
                  "%s": 18
                }
                """.formatted(ChatConstant.TOPIC_START_IDS_KEY,
                ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY));

        topicBoundaryService.rollbackAfterWithdraw(1L, 2L, Set.of(18L), 17L);

        verify(valueOperations).set(eq(boundaryKey), argThat(value -> {
            try {
                org.json.JSONObject json = new org.json.JSONObject(value);
                return json.getJSONArray(ChatConstant.TOPIC_START_IDS_KEY).toString()
                        .equals("[8,10]")
                        && json.getLong(ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY) == 17L;
            } catch (org.json.JSONException e) {
                return false;
            }
        }));
        verifyNoInteractions(vectorService);
    }
}
