package com.me.galchat.tool;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.service.DocumentReranker;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.vector.ChatHistoryVectorService;
import com.me.galchat.vector.WorldDetailVectorService;
import com.me.galchat.vector.WorldEventVectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class VectorTools {
    private static final int RERANK_TOP_N = 5;
    private static final String SOURCE_METADATA_KEY = "source";
    private static final String TIMESTAMP_METADATA_KEY = "timestamp";
    private static final String UNKNOWN_TIMESTAMP = "未知";
    private static final String WORLD_DETAIL_SOURCE = "world_detail";
    private static final String CHAT_HISTORY_SOURCE = "chat_history";
    private static final String WORLD_EVENT_SOURCE = "world_event";
    private static final String DOCUMENT_SEPARATOR = "\n---\n";

    private final ChatHistoryVectorService chatHistoryVectorService;
    private final WorldDetailVectorService worldDetailVectorService;
    private final WorldEventVectorService worldEventVectorService;
    private final IUserWorldPrefixService userWorldPrefixService;
    private final DocumentReranker documentReranker;

    @Tool(description = """
            工具描述：
            根据重写后的字符串，从多个数据库中匹配与其近似的内容。
            使用流程：
            1、判断当前信息是否出现缺失。如果是，则继续下面的步骤；如果否，则不要调用此方法。
            2、重写你希望查找的内容，使其更适合进行向量匹配。重写后的字符串应该与当前对话相关，但可以进行适当的扩展或修改，如果涉及人名与时间，应当给出具体内容。
            3、调用此方法，传入重写后的字符串，得到对应的返回值。
            注意事项：
            此方法的返回值是一个字符串，包含了从数据库中匹配到的与提问相关的内容。返回值的格式如下：
            来源: [来源名称]
            时间戳: [匹配到的内容被创建的时间戳，部分来源可能为"未知"]
            内容: [匹配到的内容]
            不同来源的内容之间用以下分隔符分隔:---
            查询到的内容可能无关联，或为空字符串，此时请忽略返回内容。
            除自动调用外，此方法不应被连续调用，请不要尝试连续多次调用此方法来获取更多信息，这可能会导致信息错误和混乱。
            """)
    public String searchInfo(@ToolParam(description = "重写后的字符串") String query, ToolContext context) {
        if (!StringUtils.hasText(query) || context == null) {
            return "";
        }

        Map<String, Object> map = context.getContext();
        if (map == null) {
            return "";
        }

        Long userWorldId = asLong(map.get("userWorldId"));
        Long characterId = asLong(map.get("characterId"));
        if (userWorldId == null || characterId == null) {
            return "";
        }

        UserWorldPrefix userWorld = userWorldPrefixService.getById(userWorldId);
        if (userWorld == null || userWorld.getWorldId() == null) {
            return "";
        }

        CompletableFuture<List<Document>> worldDetailFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> worldDetailVectorService.queryWorldDetail(userWorld.getWorldId(), query),
                        WORLD_DETAIL_SOURCE));
        CompletableFuture<List<Document>> chatHistoryFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> chatHistoryVectorService.queryChatHistory(userWorldId, characterId, query),
                        CHAT_HISTORY_SOURCE));
        CompletableFuture<List<Document>> worldEventFuture = CompletableFuture.supplyAsync(() ->
                queryWithSource(() -> worldEventVectorService.queryWorldEvent(userWorldId, characterId, query),
                        WORLD_EVENT_SOURCE));

        List<Document> documents = new ArrayList<>();
        documents.addAll(worldDetailFuture.join());
        documents.addAll(chatHistoryFuture.join());
        documents.addAll(worldEventFuture.join());

        return documentReranker.rerank(query, documents, RERANK_TOP_N).stream()
                .filter(Document::isText)
                .map(this::formatDocument)
                .filter(StringUtils::hasText)
                .limit(RERANK_TOP_N)
                .collect(Collectors.joining(DOCUMENT_SEPARATOR));
    }

    private List<Document> queryWithSource(Supplier<List<Document>> querySupplier, String source) {
        return querySupplier.get().stream()
                .map(document -> document.mutate()
                        .metadata(SOURCE_METADATA_KEY, source)
                        .build())
                .toList();
    }

    private String formatDocument(Document document) {
        String text = document.getText();
        if (!StringUtils.hasText(text)) {
            return "";
        }

        Object source = document.getMetadata().get(SOURCE_METADATA_KEY);
        if (source == null) {
            return text;
        }

        Object timestamp = document.getMetadata().get(TIMESTAMP_METADATA_KEY);
        return "来源: " + source + "\n"
                + "时间戳: " + (timestamp == null ? UNKNOWN_TIMESTAMP : timestamp) + "\n"
                + "内容: " + text;
    }

    private Long asLong(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return Long.valueOf(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
