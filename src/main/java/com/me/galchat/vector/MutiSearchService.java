package com.me.galchat.vector;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.service.DocumentReranker;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.utils.TypeConvertUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MutiSearchService {

    private static final int PRE_CHAT_WORLD_DETAIL_LIMIT = 2;
    private static final int PRE_CHAT_HISTORY_LIMIT = 1;
    private static final int PRE_CHAT_WORLD_EVENT_LIMIT = 1;

    private final ChatHistoryVectorService chatHistoryVectorService;
    private final WorldDetailVectorService worldDetailVectorService;
    private final WorldEventVectorService worldEventVectorService;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final DocumentReranker documentReranker;

    public String searchBeforeChat(Long userWorldId, Long characterId, String query) {
        if (!StringUtils.hasText(query) || userWorldId == null || characterId == null) {
            return "";
        }

        UserWorldPrefix userWorld = userWorldPrefixService.getById(userWorldId);
        if (userWorld == null || userWorld.getWorldId() == null) {
            return "";
        }

        CompletableFuture<List<Document>> worldDetailFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> worldDetailVectorService.queryWorldDetail(userWorld.getWorldId(), query),
                        VectorConstant.WORLD_DETAIL_SOURCE, PRE_CHAT_WORLD_DETAIL_LIMIT));
        CompletableFuture<List<Document>> chatHistoryFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> chatHistoryVectorService.queryChatHistory(userWorldId, characterId, query),
                        VectorConstant.CHAT_HISTORY_SOURCE, PRE_CHAT_HISTORY_LIMIT));
        CompletableFuture<List<Document>> worldEventFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> worldEventVectorService.queryWorldEvent(userWorldId, characterId, query),
                        VectorConstant.WORLD_EVENT_SOURCE, PRE_CHAT_WORLD_EVENT_LIMIT));

        List<Document> documents = new ArrayList<>();
        documents.addAll(worldDetailFuture.join());
        documents.addAll(chatHistoryFuture.join());
        documents.addAll(worldEventFuture.join());

        return documents.stream()
                .filter(Document::isText)
                .map(this::formatDocument)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(VectorConstant.DOCUMENT_SEPARATOR));
    }

    public String searchInfo(String query, ToolContext context) {
        if (!StringUtils.hasText(query) || context == null) {
            return "";
        }

        Map<String, Object> map = context.getContext();
        if (map == null) {
            return "";
        }

        Long userWorldId = TypeConvertUtils.asLong(map.get(ChatToolContextConstant.USER_WORLD_ID_KEY));
        Long characterId = TypeConvertUtils.asLong(map.get(ChatToolContextConstant.CHARACTER_ID_KEY));
        if (userWorldId == null || characterId == null) {
            return "";
        }

        return searchInfo(userWorldId, characterId, query);
    }

    public String searchInfo(Long userWorldId, Long characterId, String query) {
        if (!StringUtils.hasText(query) || userWorldId == null || characterId == null) {
            return "";
        }

        UserWorldPrefix userWorld = userWorldPrefixService.getById(userWorldId);
        if (userWorld == null || userWorld.getWorldId() == null) {
            return "";
        }

        CompletableFuture<List<Document>> worldDetailFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> worldDetailVectorService.queryWorldDetail(userWorld.getWorldId(), query),
                        VectorConstant.WORLD_DETAIL_SOURCE));
        CompletableFuture<List<Document>> chatHistoryFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> chatHistoryVectorService.queryChatHistory(userWorldId, characterId, query),
                        VectorConstant.CHAT_HISTORY_SOURCE));
        CompletableFuture<List<Document>> worldEventFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> worldEventVectorService.queryWorldEvent(userWorldId, characterId, query),
                        VectorConstant.WORLD_EVENT_SOURCE));

        List<Document> documents = new ArrayList<>();
        documents.addAll(worldDetailFuture.join());
        documents.addAll(chatHistoryFuture.join());
        documents.addAll(worldEventFuture.join());

        return documentReranker.rerank(query, documents, VectorConstant.RERANK_TOP_N).stream()
                .filter(Document::isText)
                .map(this::formatDocument)
                .filter(StringUtils::hasText)
                .limit(VectorConstant.RERANK_TOP_N)
                .collect(Collectors.joining(VectorConstant.DOCUMENT_SEPARATOR));
    }

    private List<Document> queryWithSource(Supplier<List<Document>> querySupplier, String source) {
        return queryWithSource(querySupplier, source, Integer.MAX_VALUE);
    }

    private List<Document> queryWithSource(Supplier<List<Document>> querySupplier, String source, int limit) {
        return querySupplier.get().stream()
                .limit(limit)
                .map(document -> document.mutate()
                        .metadata(VectorConstant.SOURCE_METADATA_KEY, source)
                        .build())
                .toList();
    }

    private String formatDocument(Document document) {
        String text = document.getText();
        if (!StringUtils.hasText(text)) {
            return "";
        }

        Object source = document.getMetadata().get(VectorConstant.SOURCE_METADATA_KEY);
        if (source == null) {
            return text;
        }

        Object timestamp = document.getMetadata().get(VectorConstant.TIMESTAMP_METADATA_KEY);
        return "来源: " + source + "\n"
                + "时间戳: " + (timestamp == null ? VectorConstant.UNKNOWN_TIMESTAMP : timestamp) + "\n"
                + "内容: " + text;
    }
}
