package com.me.galchat.vector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.groupchat.dice.GroupDiceMessageFormatter;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.pgvector.PGvector;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TrpgTurnVectorService {

    private static final int RECALL_LIMIT = 10;
    private static final Object[] CONVERSATION_LOCKS = new Object[32];
    private static final Set<String> PUBLIC_MEMORY_KINDS = Set.of(
            GroupChatConstant.MESSAGE_DIALOGUE,
            GroupChatConstant.MESSAGE_NARRATION,
            GroupChatConstant.MESSAGE_DICE_ROLL,
            GroupChatConstant.MESSAGE_COMBAT_RESULT,
            GroupChatConstant.MESSAGE_EPILOGUE);

    private final GroupChatTurnMapper turnMapper;
    private final GroupChatMessageMapper messageMapper;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private GroupDiceMessageFormatter diceFormatter;

    static {
        for (int index = 0; index < CONVERSATION_LOCKS.length; index++) {
            CONVERSATION_LOCKS[index] = new Object();
        }
    }

    public TrpgTurnVectorService(
            GroupChatTurnMapper turnMapper,
            GroupChatMessageMapper messageMapper,
            @Qualifier("trpgTurnVectorStore") VectorStore vectorStore,
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate) {
        this.turnMapper = turnMapper;
        this.messageMapper = messageMapper;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Autowired
    void setDiceFormatter(GroupDiceMessageFormatter diceFormatter) {
        this.diceFormatter = diceFormatter;
    }

    public void indexTurn(Long turnId) {
        GroupChatTurn initial = turnId == null
                ? null : turnMapper.selectById(turnId);
        if (initial == null || initial.getConversationId() == null) {
            return;
        }
        synchronized (lock(initial.getConversationId())) {
            GroupChatTurn turn = turnMapper.selectById(turnId);
            if (turn == null || !GroupChatConstant.STATUS_COMPLETED.equals(
                    turn.getStatus())) {
                vectorStore.delete(List.of(documentId(turnId)));
                return;
            }
            List<GroupChatMessage> messages = messages(
                    turn.getConversationId(), turnId);
            String text = indexableText(turn, messages);
            if (!StringUtils.hasText(text)) {
                vectorStore.delete(List.of(documentId(turnId)));
                return;
            }
            vectorStore.add(List.of(document(turn, text)));
        }
    }

    public List<Candidate> search(Long conversationId, String keyword) {
        if (conversationId == null || !StringUtils.hasText(keyword)) {
            return List.of();
        }
        synchronized (lock(conversationId)) {
            indexMissingTurns(conversationId);
            PGvector query = new PGvector(embeddingModel.embed(keyword.trim()));
            String sql = """
                SELECT (metadata::jsonb ->> 'turnId')::bigint AS turn_id,
                       (metadata::jsonb ->> 'occurredAtEpochMilli')::bigint AS occurred_at,
                       1 - (embedding <=> ?) AS similarity
                FROM public.trpg_turn_vector_store
                WHERE metadata::jsonb @> ?::jsonb
                ORDER BY embedding <=> ?
                LIMIT ?
                """;
            List<Candidate> recalled = jdbcTemplate.query(sql,
                    (row, index) -> new Candidate(
                            row.getLong("turn_id"),
                            LocalDateTime.ofEpochSecond(
                                    row.getLong("occurred_at") / 1000,
                                    (int) (row.getLong("occurred_at") % 1000)
                                            * 1_000_000,
                                    ZoneOffset.UTC),
                            row.getDouble("similarity")),
                    query, "{\"conversationId\":%d}".formatted(conversationId),
                    query, RECALL_LIMIT);
            return rank(recalled, 3);
        }
    }

    private void indexMissingTurns(Long conversationId) {
        List<GroupChatTurn> turns = completedTurns(conversationId);
        if (turns.isEmpty()) {
            return;
        }
        List<Long> indexed = jdbcTemplate.queryForList("""
                        SELECT (metadata::jsonb ->> 'turnId')::bigint
                        FROM public.trpg_turn_vector_store
                        WHERE metadata::jsonb @> ?::jsonb
                        """,
                Long.class,
                "{\"conversationId\":%d}".formatted(conversationId));
        Set<Long> indexedIds = new HashSet<>(indexed);
        List<GroupChatTurn> missingTurns = turns.stream()
                .filter(turn -> !indexedIds.contains(turn.getId()))
                .toList();
        Map<Long, List<GroupChatMessage>> messagesByTurn =
                messagesForTurns(conversationId, missingTurns.stream()
                        .map(GroupChatTurn::getId).toList());
        List<Document> missing = new ArrayList<>();
        for (GroupChatTurn turn : missingTurns) {
            String text = indexableText(
                    turn, messagesByTurn.get(turn.getId()));
            if (StringUtils.hasText(text)) {
                missing.add(document(turn, text));
            }
        }
        if (!missing.isEmpty()) {
            vectorStore.add(missing);
        }
    }

    static List<Candidate> rank(List<Candidate> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        long oldest = candidates.stream().map(Candidate::occurredAt)
                .filter(java.util.Objects::nonNull)
                .mapToLong(value -> value.toInstant(ZoneOffset.UTC).toEpochMilli())
                .min().orElse(0L);
        long newest = candidates.stream().map(Candidate::occurredAt)
                .filter(java.util.Objects::nonNull)
                .mapToLong(value -> value.toInstant(ZoneOffset.UTC).toEpochMilli())
                .max().orElse(oldest);
        long range = newest - oldest;
        return candidates.stream().map(candidate -> {
                    long time = candidate.occurredAt() == null ? oldest
                            : candidate.occurredAt().toInstant(
                                    ZoneOffset.UTC).toEpochMilli();
                    double recency = range == 0 ? 1.0
                            : (double) (time - oldest) / range;
                    double score = 0.90 * candidate.similarity()
                            + 0.10 * recency;
                    return new Candidate(candidate.turnId(),
                            candidate.occurredAt(), candidate.similarity(),
                            Math.round(score * 1000.0) / 1000.0);
                })
                .sorted(Comparator.comparingDouble(Candidate::score)
                        .reversed()
                        .thenComparing(Candidate::occurredAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Candidate::turnId,
                                Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    String indexableText(
            GroupChatTurn turn, List<GroupChatMessage> candidates) {
        if (turn == null || turn.getId() == null || candidates == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        candidates.stream()
                .filter(message -> java.util.Objects.equals(
                        turn.getId(), message.getTurnId()))
                .filter(message -> "public".equals(message.getVisibility()))
                .filter(message -> GroupChatConstant.STATUS_COMPLETED.equals(
                        message.getStatus()))
                .filter(message -> PUBLIC_MEMORY_KINDS.contains(
                        message.getMessageKind()))
                .sorted(Comparator.comparing(GroupChatMessage::getSequenceNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(message -> text.append('[')
                        .append(message.getSpeakerType())
                        .append("] ")
                        .append(indexContent(message))
                        .append('\n'));
        return text.toString().trim();
    }

    private String indexContent(GroupChatMessage message) {
        if (diceFormatter != null
                && GroupChatConstant.MESSAGE_DICE_ROLL.equals(
                message.getMessageKind())) {
            return diceFormatter.format(message.getContent());
        }
        return message.getContent() == null ? "" : message.getContent();
    }

    private List<GroupChatTurn> completedTurns(Long conversationId) {
        return turnMapper.selectList(new LambdaQueryWrapper<GroupChatTurn>()
                .eq(GroupChatTurn::getConversationId, conversationId)
                .eq(GroupChatTurn::getStatus,
                        GroupChatConstant.STATUS_COMPLETED)
                .orderByAsc(GroupChatTurn::getId));
    }

    private List<GroupChatMessage> messages(
            Long conversationId, Long turnId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId,
                                conversationId)
                        .eq(GroupChatMessage::getTurnId, turnId)
                        .eq(GroupChatMessage::getVisibility, "public")
                        .eq(GroupChatMessage::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .orderByAsc(GroupChatMessage::getSequenceNo));
    }

    private Map<Long, List<GroupChatMessage>> messagesForTurns(
            Long conversationId, List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return Map.of();
        }
        return messageMapper.selectList(
                        new LambdaQueryWrapper<GroupChatMessage>()
                                .eq(GroupChatMessage::getConversationId,
                                        conversationId)
                                .in(GroupChatMessage::getTurnId, turnIds)
                                .eq(GroupChatMessage::getVisibility,
                                        "public")
                                .eq(GroupChatMessage::getStatus,
                                        GroupChatConstant.STATUS_COMPLETED)
                                .orderByAsc(
                                        GroupChatMessage::getSequenceNo))
                .stream()
                .filter(message -> message.getTurnId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        GroupChatMessage::getTurnId));
    }

    private Document document(GroupChatTurn turn, String text) {
        LocalDateTime occurredAt = turn.getUpdatedAt() == null
                ? turn.getCreatedAt() : turn.getUpdatedAt();
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("conversationId", turn.getConversationId());
        metadata.put("turnId", turn.getId());
        metadata.put("occurredAtEpochMilli", occurredAt.toInstant(
                ZoneOffset.UTC).toEpochMilli());
        return Document.builder().id(documentId(turn.getId()))
                .text(text).metadata(metadata).build();
    }

    private Object lock(Long conversationId) {
        return CONVERSATION_LOCKS[Math.floorMod(
                Long.hashCode(conversationId), CONVERSATION_LOCKS.length)];
    }

    private String documentId(Long turnId) {
        return UUID.nameUUIDFromBytes(
                ("trpg-turn:" + turnId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    public record Candidate(
            Long turnId,
            LocalDateTime occurredAt,
            double similarity,
            double score) {
        public Candidate(
                Long turnId, LocalDateTime occurredAt, double similarity) {
            this(turnId, occurredAt, similarity, similarity);
        }
    }
}
