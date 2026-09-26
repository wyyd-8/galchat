package com.me.galchat.vector;

import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.UserChatHistoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatHistoryCompressionTest {
    @Test void roleInversionRegressionAndRelativeTimeKeepOriginalOwnership() {
        var fixture = new Fixture();
        when(fixture.client.prompt().user(anyString()).call().content()).thenReturn("""
                {"messages":[{"messageId":"373","compressedContent":"（摸摸脑袋）他可能明天来。"},
                {"messageId":"374","compressedContent":"我没说一定来。"}]}
                """);
        fixture.service.addChatHistory(1L, 2L, 373L, 375L);
        ArgumentCaptor<List<Document>> docs = ArgumentCaptor.forClass(List.class);
        verify(fixture.store).add(docs.capture());
        assertThat(docs.getValue().getFirst().getText()).isEqualTo("""
                [2026-09-26T10:00] user: （摸摸脑袋）他可能明天来。
                [2026-09-26T10:01] assistant: 我没说一定来。""");
    }
    @Test void malformedSummaryIsNeverEmbedded() {
        var fixture = new Fixture();
        when(fixture.client.prompt().user(anyString()).call().content()).thenReturn("assistant: 他明天会来");
        assertThatThrownBy(() -> fixture.service.addChatHistory(1L, 2L, 373L, 375L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(fixture.store);
    }
    private static class Fixture {
        final UserChatHistoryMapper mapper = mock(UserChatHistoryMapper.class);
        final ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        final VectorStore store = mock(VectorStore.class);
        final ChatHistoryVectorService service = new ChatHistoryVectorService(mapper, client, store, mock(DocumentRetriever.class));
        Fixture() {
            when(mapper.selectList(any())).thenReturn(List.of(
                    new UserChatHistory().setId(373L).setType("user").setContent("（摸摸脑袋）他可能明天来。")
                            .setTimestamp(LocalDateTime.of(2026,9,26,10,0)),
                    new UserChatHistory().setId(374L).setType("assistant").setContent("我没说一定来。")
                            .setTimestamp(LocalDateTime.of(2026,9,26,10,1))));
        }
    }
}
