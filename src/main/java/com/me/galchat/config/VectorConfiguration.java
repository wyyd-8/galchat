package com.me.galchat.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
public class VectorConfiguration {
    @Bean(name = "worldDetailVectorStore")
    public VectorStore worldDetailVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024) // 可选：默认为模型维度或 1536
                .distanceType(COSINE_DISTANCE) // 可选：默认为 COSINE_DISTANCE
                .indexType(HNSW) // 可选：默认为 HNSW
                .initializeSchema(true) // 可选：默认为 false
                .schemaName("public") // 可选：默认为 "public"
                .vectorTableName("world_detail_vector_store") // 可选：默认为 "vector_store"
                .maxDocumentBatchSize(10000) // 可选：默认为 10000
                .build();
    }

    @Bean(name = "chatHistoryVectorStore")
    public VectorStore chatHistoryVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024) // 可选：默认为模型维度或 1536
                .distanceType(COSINE_DISTANCE) // 可选：默认为 COSINE_DISTANCE
                .indexType(HNSW) // 可选：默认为 HNSW
                .initializeSchema(true) // 可选：默认为 false
                .schemaName("public") // 可选：默认为 "public"
                .vectorTableName("chat_history_vector_store") // 可选：默认为 "vector_store"
                .maxDocumentBatchSize(10000) // 可选：默认为 10000
                .build();
    }

    @Bean(name = "worldEventVectorStore")
    public VectorStore worldEventVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024) // 可选：默认为模型维度或 1536
                .distanceType(COSINE_DISTANCE) // 可选：默认为 COSINE_DISTANCE
                .indexType(HNSW) // 可选：默认为 HNSW
                .initializeSchema(true) // 可选：默认为 false
                .schemaName("public") // 可选：默认为 "public"
                .vectorTableName("world_event_vector_store") // 可选：默认为 "vector_store"
                .maxDocumentBatchSize(10000) // 可选：默认为 10000
                .build();
    }

    @Bean(name = "userEventVectorStore")
    public VectorStore userEventVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024) // 可选：默认为模型维度或 1536
                .distanceType(COSINE_DISTANCE) // 可选：默认为 COSINE_DISTANCE
                .indexType(HNSW) // 可选：默认为 HNSW
                .initializeSchema(true) // 可选：默认为 false
                .schemaName("public") // 可选：默认为 "public"
                .vectorTableName("user_event_vector_store") // 可选：默认为 "vector_store"
                .maxDocumentBatchSize(10000) // 可选：默认为 10000
                .build();
    }
}
