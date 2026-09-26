package com.me.galchat.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTopic;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.memory.TopicSummaryCodec;
import com.me.galchat.memory.TopicModelCall;
import com.me.galchat.memory.TopicCompressionPrompts;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.pgvector.PGvector;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GroupTopicVectorService {

    private final GroupChatMessageMapper messageMapper;
    private final GroupChatMemberMapper memberMapper;
    private final ChatClient rewriteClient;
    private final VectorStore vectorStore;
    private final DocumentRetriever retriever;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public GroupTopicVectorService(
            GroupChatMessageMapper messageMapper,
            GroupChatMemberMapper memberMapper,
            @Qualifier("rewriteClient") ChatClient rewriteClient,
            @Qualifier("groupTopicVectorStore") VectorStore vectorStore,
            @Qualifier("groupTopicRetriever") DocumentRetriever retriever,
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate) {
        this.messageMapper = messageMapper;
        this.memberMapper = memberMapper;
        this.rewriteClient = rewriteClient;
        this.vectorStore = vectorStore;
        this.retriever = retriever;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void addTopic(GroupConversation conversation, GroupChatTopic topic, Long endSequence) {
        Long startSequence = topic == null ? null : topic.getStartSequence();
        if (startSequence == null || endSequence == null || startSequence >= endSequence) {
            return;
        }
        List<GroupChatMessage> messages = messages(conversation.getId(), startSequence, endSequence);
        if (messages.isEmpty()) {
            return;
        }
        List<Long> visibleCharacters = visibleCharacters(conversation.getId());
        if (visibleCharacters.isEmpty()) {
            return;
        }
        List<TopicSummaryCodec.Item> source = messages.stream()
                .filter(message -> StringUtils.hasText(message.getContent()))
                .map(message -> new TopicSummaryCodec.Item(message.getId().toString(),
                        message.getSpeakerType() + (message.getSpeakerId() == null ? "" : ":" + message.getSpeakerId()),
                        message.getCreatedAt() == null ? "" : message.getCreatedAt().toString(), message.getContent()))
                .toList();
        if (source.isEmpty()) {
            return;
        }
        String rewritten = TopicModelCall.read(() -> rewriteClient.prompt()
                .system(TopicCompressionPrompts.SUMMARIZE)
                .user(TopicSummaryCodec.input(source))
                .call().content(), TopicModelCall.SUMMARY_TIMEOUT);
        rewritten = TopicSummaryCodec.render(source, rewritten);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(VectorConstant.USER_WORLD_ID_METADATA_KEY, conversation.getUserWorldId());
        metadata.put(VectorConstant.CONVERSATION_ID_METADATA_KEY, conversation.getId());
        metadata.put(VectorConstant.GROUP_TOPIC_ID_METADATA_KEY, topic.getId());
        metadata.put(VectorConstant.START_SEQUENCE_METADATA_KEY, startSequence);
        metadata.put(VectorConstant.END_SEQUENCE_METADATA_KEY, endSequence);
        metadata.put(VectorConstant.VISIBLE_CHARACTERS_METADATA_KEY, visibleCharacters);
        vectorStore.add(List.of(Document.builder()
                .id(documentId(conversation.getId(), startSequence, endSequence))
                .text(rewritten.trim())
                .metadata(metadata)
                .build()));
    }

    public String search(Long conversationId, Long windowStartSequence, String question) {
        if (conversationId == null || windowStartSequence == null || !StringUtils.hasText(question)) {
            return "";
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.and(
                builder.eq(VectorConstant.CONVERSATION_ID_METADATA_KEY, conversationId),
                builder.lte(VectorConstant.END_SEQUENCE_METADATA_KEY, windowStartSequence)).build();
        List<Document> documents = retriever.retrieve(Query.builder()
                .text(question)
                .context(Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filter))
                .build());
        return documents.stream()
                .filter(Document::isText)
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(VectorConstant.DOCUMENT_SEPARATOR));
    }

    public List<Document> queryGroupTopics(Long userWorldId, Long characterId, String question) {
        Assert.notNull(userWorldId, "userWorldId cannot be null");
        Assert.notNull(characterId, "characterId cannot be null");
        Assert.hasText(question, "question cannot be blank");

        PGvector queryEmbedding = new PGvector(embeddingModel.embed(question));
        String sql = """
                SELECT *, embedding <=> ? AS distance
                FROM public.group_topic_vector_store
                WHERE embedding <=> ? < ?
                  AND metadata::jsonb @> ?::jsonb
                  AND metadata::jsonb -> 'visibleCharacters' @> ?::jsonb
                ORDER BY distance
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, this::mapDocument,
                queryEmbedding,
                queryEmbedding,
                VectorConstant.GROUP_TOPIC_DISTANCE_THRESHOLD,
                "{\"userWorldId\":%d}".formatted(userWorldId),
                "[%d]".formatted(characterId),
                VectorConstant.GROUP_TOPIC_TOP_K);
    }

    public void deleteTopic(Long conversationId, Long startSequence, Long endSequence) {
        if (conversationId == null || startSequence == null || endSequence == null
                || startSequence >= endSequence) {
            return;
        }
        vectorStore.delete(List.of(documentId(conversationId, startSequence, endSequence)));
    }

    private Document mapDocument(ResultSet resultSet, int rowNum) throws SQLException {
        Map<String, Object> metadata = jsonMapper.readValue(resultSet.getString("metadata"), Map.class);
        float distance = resultSet.getFloat("distance");
        metadata.put(DocumentMetadata.DISTANCE.value(), distance);
        return Document.builder()
                .id(resultSet.getString("id"))
                .text(resultSet.getString("content"))
                .metadata(metadata)
                .score(1 - (double) distance)
                .build();
    }

    private List<GroupChatMessage> messages(Long conversationId, Long startSequence, Long endSequence) {
        return messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversationId)
                .ge(GroupChatMessage::getSequenceNo, startSequence)
                .lt(GroupChatMessage::getSequenceNo, endSequence)
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED)
                .eq(GroupChatMessage::getVisibility, "public")
                .orderByAsc(GroupChatMessage::getSequenceNo));
    }

    private List<Long> visibleCharacters(Long conversationId) {
        return memberMapper.selectList(new LambdaQueryWrapper<GroupChatMember>()
                        .eq(GroupChatMember::getConversationId, conversationId)
                        .eq(GroupChatMember::getActorType, GroupChatConstant.ACTOR_CHARACTER)
                        .eq(GroupChatMember::getEnabled, true)
                        .orderByAsc(GroupChatMember::getPosition)
                        .orderByAsc(GroupChatMember::getId))
                .stream()
                .map(GroupChatMember::getActorId)
                .distinct()
                .toList();
    }


    private String documentId(Long conversationId, Long startSequence, Long endSequence) {
        String source = "%s:%d:%d:%d".formatted(
                VectorConstant.GROUP_TOPIC_ID_PREFIX, conversationId, startSequence, endSequence);
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
