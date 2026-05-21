package com.me.galchat.tool;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.service.DocumentReranker;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.vector.ChatHistoryVectorService;
import com.me.galchat.vector.WorldDetailVectorService;
import com.me.galchat.vector.WorldEventVectorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VectorToolsTest {

    @Test
    void searchInfoReranksRetrievedDocumentsAndJoinsTopFiveText() {
        Document worldDetail = document("world-detail", "世界设定");
        Document chatHistory = document("chat-history", "聊天历史", "2026-05-20T10:00");
        Document worldEvent = document("world-event", "世界事件", "2026-05-21T12:30");

        FakeWorldDetailVectorService worldDetailVectorService = new FakeWorldDetailVectorService(List.of(worldDetail));
        FakeChatHistoryVectorService chatHistoryVectorService = new FakeChatHistoryVectorService(List.of(chatHistory));
        FakeWorldEventVectorService worldEventVectorService = new FakeWorldEventVectorService(List.of(worldEvent));
        RecordingReranker documentReranker = new RecordingReranker(List.of("world-event", "chat-history", "world-detail"));
        VectorTools vectorTools = new VectorTools(
                chatHistoryVectorService,
                worldDetailVectorService,
                worldEventVectorService,
                userWorldPrefixService(new UserWorldPrefix().setId(10L).setWorldId(20L)),
                documentReranker
        );

        String result = vectorTools.searchInfo("主角在哪里",
                new ToolContext(Map.of("userWorldId", 10L, "characterId", 30L)));

        assertThat(result).isEqualTo("""
                来源: world_event
                时间戳: 2026-05-21T12:30
                内容: 世界事件
                ---
                来源: chat_history
                时间戳: 2026-05-20T10:00
                内容: 聊天历史
                ---
                来源: world_detail
                时间戳: 未知
                内容: 世界设定""");
        assertThat(worldDetailVectorService.worldId).isEqualTo(20L);
        assertThat(chatHistoryVectorService.userWorldId).isEqualTo(10L);
        assertThat(worldEventVectorService.characterId).isEqualTo(30L);
        assertThat(documentReranker.documents).extracting(document -> document.getMetadata().get("source"))
                .containsExactly("world_detail", "chat_history", "world_event");
        assertThat(documentReranker.topN).isEqualTo(5);
    }

    @Test
    void searchInfoQueriesVectorServicesInParallel() {
        CountDownLatch started = new CountDownLatch(3);
        FakeWorldDetailVectorService worldDetailVectorService = new FakeWorldDetailVectorService(
                List.of(document("world-detail", "世界设定")), started);
        FakeChatHistoryVectorService chatHistoryVectorService = new FakeChatHistoryVectorService(
                List.of(document("chat-history", "聊天历史")), started);
        FakeWorldEventVectorService worldEventVectorService = new FakeWorldEventVectorService(
                List.of(document("world-event", "世界事件")), started);
        VectorTools vectorTools = new VectorTools(
                chatHistoryVectorService,
                worldDetailVectorService,
                worldEventVectorService,
                userWorldPrefixService(new UserWorldPrefix().setId(10L).setWorldId(20L)),
                new RecordingReranker(List.of("world-detail", "chat-history", "world-event"))
        );

        String result = vectorTools.searchInfo("主角在哪里",
                new ToolContext(Map.of("userWorldId", 10L, "characterId", 30L)));

        assertThat(result).contains("世界设定", "聊天历史", "世界事件");
    }

    @Test
    void searchInfoReturnsBlankWhenContextIsIncomplete() {
        VectorTools vectorTools = new VectorTools(
                new FakeChatHistoryVectorService(List.of()),
                new FakeWorldDetailVectorService(List.of()),
                new FakeWorldEventVectorService(List.of()),
                userWorldPrefixService(new UserWorldPrefix().setId(10L).setWorldId(20L)),
                new RecordingReranker(List.of())
        );

        String result = vectorTools.searchInfo("主角在哪里", new ToolContext(Map.of("userWorldId", 10L)));

        assertThat(result).isEmpty();
    }

    private static Document document(String id, String text) {
        return Document.builder()
                .id(id)
                .text(text)
                .build();
    }

    private static Document document(String id, String text, String timestamp) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata("timestamp", timestamp)
                .build();
    }

    private static IUserWorldPrefixService userWorldPrefixService(UserWorldPrefix userWorldPrefix) {
        return (IUserWorldPrefixService) Proxy.newProxyInstance(
                IUserWorldPrefixService.class.getClassLoader(),
                new Class<?>[]{IUserWorldPrefixService.class},
                (proxy, method, args) -> "getById".equals(method.getName()) ? userWorldPrefix : null
        );
    }

    private static final class FakeWorldDetailVectorService extends WorldDetailVectorService {
        private final List<Document> documents;
        private final CountDownLatch started;
        private Long worldId;

        private FakeWorldDetailVectorService(List<Document> documents) {
            this(documents, null);
        }

        private FakeWorldDetailVectorService(List<Document> documents, CountDownLatch started) {
            super(null, null);
            this.documents = documents;
            this.started = started;
        }

        @Override
        public List<Document> queryWorldDetail(Long worldId, String question) {
            this.worldId = worldId;
            awaitOtherQueries(started);
            return documents;
        }
    }

    private static final class FakeChatHistoryVectorService extends ChatHistoryVectorService {
        private final List<Document> documents;
        private final CountDownLatch started;
        private Long userWorldId;

        private FakeChatHistoryVectorService(List<Document> documents) {
            this(documents, null);
        }

        private FakeChatHistoryVectorService(List<Document> documents, CountDownLatch started) {
            super(null, null, null, null);
            this.documents = documents;
            this.started = started;
        }

        @Override
        public List<Document> queryChatHistory(Long userWorldId, Long characterId, String question) {
            this.userWorldId = userWorldId;
            awaitOtherQueries(started);
            return documents;
        }
    }

    private static final class FakeWorldEventVectorService extends WorldEventVectorService {
        private final List<Document> documents;
        private final CountDownLatch started;
        private Long characterId;

        private FakeWorldEventVectorService(List<Document> documents) {
            this(documents, null);
        }

        private FakeWorldEventVectorService(List<Document> documents, CountDownLatch started) {
            super(null, null, null);
            this.documents = documents;
            this.started = started;
        }

        @Override
        public List<Document> queryWorldEvent(Long userWorldId, Long characterId, String question) {
            this.characterId = characterId;
            awaitOtherQueries(started);
            return documents;
        }
    }

    private static final class RecordingReranker implements DocumentReranker {
        private final List<String> rankedDocumentIds;
        private List<Document> documents;
        private int topN;

        private RecordingReranker(List<String> rankedDocumentIds) {
            this.rankedDocumentIds = rankedDocumentIds;
        }

        @Override
        public List<Document> rerank(String query, List<Document> documents, int topN) {
            this.documents = documents;
            this.topN = topN;
            return rankedDocumentIds.stream()
                    .flatMap(id -> documents.stream().filter(document -> id.equals(document.getId())).limit(1))
                    .toList();
        }
    }

    private static void awaitOtherQueries(CountDownLatch started) {
        if (started == null) {
            return;
        }
        started.countDown();
        try {
            if (!started.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("vector queries should run in parallel");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
