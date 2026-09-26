package com.me.galchat.memory;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.vector.ChatHistoryVectorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TopicBoundaryForwardTest {
    @Test
    void evaluatesOnlyCurrentTopicAndIgnoresMessagesWrittenAfterTrigger() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{\"%s\":[10,20,30]}".formatted(ChatConstant.TOPIC_START_IDS_KEY));
        var memory = mock(UserChatMemory.class);
        var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(anyString()).call().content()).thenReturn("{\"score\":10}");
        var current = message(32, "继续说");
        when(memory.listHistories(any())).thenAnswer(invocation -> {
            ConversationInfo info = invocation.getArgument(0);
            assertThat(info.getStart()).isEqualTo(30L);
            return List.of(message(30, "短话题"), message(31, "回答"), current,
                    message(33, "本轮生成的新回复".repeat(500)));
        });
        var service = new TopicBoundaryService(redis, client, memory, mock(ChatHistoryVectorService.class));
        assertThat(service.updateAfterUserMessage(new ConversationInfo(1L, 2L, 10L), current).startIds())
                .containsExactly(10L, 20L, 30L);
    }

    private UserChatHistory message(long id, String text) {
        return new UserChatHistory().setId(id).setUserWorldId(1L).setCharacterId(2L)
                .setType("user").setContent(text);
    }
}
