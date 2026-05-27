package com.me.galchat.vector;

import com.me.galchat.constant.DateTimeConstant;
import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.WorldEventLog;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorldEventVectorService {

    private final VectorStore worldEventVectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public void addWorldEventLog(WorldEventLog worldEventLog) {
        Assert.notNull(worldEventLog, "worldEventLog cannot be null");
        Assert.notNull(worldEventLog.getId(), "worldEventLog id cannot be null");
        Assert.notNull(worldEventLog.getUserWorldId(), "userWorldId cannot be null");

        if (!StringUtils.hasText(worldEventLog.getEventDescription()) || worldEventLog.getVisibleCharacters() == null) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(VectorConstant.USER_WORLD_ID_METADATA_KEY, worldEventLog.getUserWorldId());
        metadata.put(VectorConstant.VISIBLE_CHARACTERS_METADATA_KEY, Arrays.asList(worldEventLog.getVisibleCharacters()));
        if (StringUtils.hasText(worldEventLog.getTitle())) {
            metadata.put(VectorConstant.TITLE_METADATA_KEY, worldEventLog.getTitle());
        }
        if (worldEventLog.getTimestamp() != null) {
            metadata.put(VectorConstant.TIMESTAMP_METADATA_KEY,
                    worldEventLog.getTimestamp().format(DateTimeConstant.DATE_TIME_FORMATTER));
        }

        Document document = Document.builder()
                .id(vectorDocumentId(worldEventLog.getId()))
                .text(formatDocumentText(worldEventLog))
                .metadata(metadata)
                .build();
        worldEventVectorStore.add(List.of(document));
    }

    public List<Document> queryWorldEvent(Long userWorldId, Long characterId, String question) {
        Assert.notNull(userWorldId, "userWorldId cannot be null");
        Assert.notNull(characterId, "characterId cannot be null");
        Assert.hasText(question, "question cannot be blank");

        PGvector queryEmbedding = new PGvector(embeddingModel.embed(question));
        String sql = """
                SELECT *, embedding <=> ? AS distance
                FROM public.world_event_vector_store
                WHERE embedding <=> ? < ?
                  AND metadata::jsonb @> ?::jsonb
                  AND metadata::jsonb -> 'visibleCharacters' @> ?::jsonb
                ORDER BY distance
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, this::mapDocument,
                queryEmbedding,
                queryEmbedding,
                VectorConstant.WORLD_EVENT_DISTANCE_THRESHOLD,
                "{\"userWorldId\":%d}".formatted(userWorldId),
                "[%d]".formatted(characterId),
                VectorConstant.WORLD_EVENT_TOP_K);
    }

    private Document mapDocument(ResultSet resultSet, int rowNum) throws SQLException {
        String metadataJson = resultSet.getString("metadata");
        Map<String, Object> metadata = jsonMapper.readValue(metadataJson, Map.class);
        float distance = resultSet.getFloat("distance");
        metadata.put(DocumentMetadata.DISTANCE.value(), distance);

        return Document.builder()
                .id(resultSet.getString("id"))
                .text(resultSet.getString("content"))
                .metadata(metadata)
                .score(1 - (double) distance)
                .build();
    }

    private String vectorDocumentId(Long worldEventLogId) {
        String idSource = "%s:%d".formatted(VectorConstant.WORLD_EVENT_ID_PREFIX, worldEventLogId);
        return UUID.nameUUIDFromBytes(idSource.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String formatDocumentText(WorldEventLog worldEventLog) {
        if (!StringUtils.hasText(worldEventLog.getTitle())) {
            return worldEventLog.getEventDescription();
        }
        return "标题: " + worldEventLog.getTitle() + "\n内容: " + worldEventLog.getEventDescription();
    }
}
