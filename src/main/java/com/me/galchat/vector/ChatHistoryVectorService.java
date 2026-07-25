package com.me.galchat.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.DateTimeConstant;
import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.mapper.UserChatHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatHistoryVectorService {

    private final UserChatHistoryMapper userChatHistoryMapper;
    private final ChatClient rewriteClient;
    private final VectorStore chatHistoryVectorStore;
    private final DocumentRetriever chatHistoryRetriever;

    public void addChatHistory(Long userWorldId, Long characterId, Long start, Long end) {
        Assert.notNull(userWorldId, "userWorldId cannot be null");
        Assert.notNull(characterId, "characterId cannot be null");
        Assert.notNull(start, "start cannot be null");
        Assert.notNull(end, "end cannot be null");
        Assert.isTrue(start < end, "start must be less than end");

        List<UserChatHistory> histories = userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .ge(UserChatHistory::getId, start)
                .lt(UserChatHistory::getId, end)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .notIn(UserChatHistory::getType, List.of(ChatConstant.SYSTEM_TYPE, ChatConstant.TOOL_TYPE,
                                ChatConstant.AUTO_SEARCH_INFO_TYPE)))
                .orderByAsc(UserChatHistory::getId));

        if (histories.isEmpty()) {
            return;
        }

        String rewrittenContent = rewriteClient.prompt()
                .user(formatHistories(histories))
                .call()
                .content();

        if (!StringUtils.hasText(rewrittenContent)) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(VectorConstant.USER_WORLD_ID_METADATA_KEY, userWorldId);
        metadata.put(VectorConstant.CHARACTER_ID_METADATA_KEY, characterId);
        metadata.put(VectorConstant.START_MESSAGE_ID_METADATA_KEY, start);
        metadata.put(VectorConstant.END_MESSAGE_ID_METADATA_KEY, end);
        if (histories.getFirst().getTimestamp() != null) {
            metadata.put(VectorConstant.TIMESTAMP_METADATA_KEY,
                    histories.getFirst().getTimestamp().format(DateTimeConstant.DATE_TIME_FORMATTER));
        }

        Document document = Document.builder()
                .id(vectorDocumentId(userWorldId, characterId, start, end))
                .text(rewrittenContent)
                .metadata(metadata)
                .build();
        chatHistoryVectorStore.add(List.of(document));
    }

    public List<Document> queryChatHistory(Long userWorldId, Long characterId, String question) {
        Assert.notNull(userWorldId, "userWorldId cannot be null");
        Assert.notNull(characterId, "characterId cannot be null");
        Assert.hasText(question, "question cannot be blank");

        Query query = Query.builder()
                .text(question)
                .context(Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filterByConversation(userWorldId, characterId)))
                .build();
        return chatHistoryRetriever.retrieve(query);
    }

    public void deleteChatHistory(Long userWorldId, Long characterId, Long start, Long end) {
        if (userWorldId == null || characterId == null || start == null || end == null || start >= end) {
            return;
        }
        chatHistoryVectorStore.delete(List.of(vectorDocumentId(userWorldId, characterId, start, end)));
    }

    private Filter.Expression filterByConversation(Long userWorldId, Long characterId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.and(
                builder.eq(VectorConstant.USER_WORLD_ID_METADATA_KEY, userWorldId),
                builder.eq(VectorConstant.CHARACTER_ID_METADATA_KEY, characterId)
        ).build();
    }

    private String formatHistories(List<UserChatHistory> histories) {
        StringBuilder builder = new StringBuilder();
        for (UserChatHistory history : histories) {
            builder.append(formatHistory(history)).append('\n');
        }
        return builder.toString();
    }

    private String formatHistory(UserChatHistory history) {
        String type = StringUtils.hasText(history.getType()) ? history.getType() : "user";
        String timestamp = history.getTimestamp() == null
                ? ""
                : history.getTimestamp().format(DateTimeConstant.DATE_TIME_FORMATTER);
        return type + " " + timestamp + ": \n" + history.getContent();
    }

    private String vectorDocumentId(Long userWorldId, Long characterId, Long start, Long end) {
        String idSource = "%s:%d:%d:%d:%d".formatted(VectorConstant.CHAT_HISTORY_ID_PREFIX,
                userWorldId, characterId, start, end);
        return UUID.nameUUIDFromBytes(idSource.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
