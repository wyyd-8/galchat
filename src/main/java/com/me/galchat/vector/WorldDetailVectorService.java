package com.me.galchat.vector;

import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.WorldDetail;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorldDetailVectorService {

    private final VectorStore worldDetailVectorStore;
    private final DocumentRetriever worldDetailRetriever;

    public void addWorldDetail(WorldDetail worldDetail) {
        Assert.notNull(worldDetail, "worldDetail cannot be null");
        Assert.notNull(worldDetail.getId(), "worldDetail id cannot be null");
        Assert.notNull(worldDetail.getWorldId(), "worldId cannot be null");

        if (!StringUtils.hasText(worldDetail.getDetails())) {
            return;
        }

        Document document = Document.builder()
                .id(vectorDocumentId(worldDetail.getId()))
                .text(worldDetail.getDetails())
                .metadata(VectorConstant.WORLD_ID_METADATA_KEY, worldDetail.getWorldId())
                .build();
        worldDetailVectorStore.add(List.of(document));
    }

    public List<Document> queryWorldDetail(Long worldId, String question) {
        Assert.notNull(worldId, "worldId cannot be null");
        Assert.hasText(question, "question cannot be blank");

        Query query = Query.builder()
                .text(question)
                .context(Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filterByWorld(worldId)))
                .build();
        return worldDetailRetriever.retrieve(query);
    }

    private Filter.Expression filterByWorld(Long worldId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.eq(VectorConstant.WORLD_ID_METADATA_KEY, worldId).build();
    }

    private String vectorDocumentId(Long worldDetailId) {
        String idSource = "%s:%d".formatted(VectorConstant.WORLD_DETAIL_ID_PREFIX, worldDetailId);
        return UUID.nameUUIDFromBytes(idSource.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
