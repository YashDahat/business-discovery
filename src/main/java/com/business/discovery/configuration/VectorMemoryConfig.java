package com.business.discovery.configuration;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Semantic chat-memory beans (Cline project chat only). Gated by {@code cline.memory.semantic.enabled}
 * (default on) so the ONNX model + pgvector table aren't created when disabled.
 *
 * The embedding model is the in-process all-MiniLM-L6-v2 (384-dim, ONNX — requires a glibc runtime image).
 * The store reuses the app's Postgres {@link DataSource}; on build it creates the {@code vector} extension
 * (needs the extension installed in the DB image) and the embeddings table.
 */
@Configuration
@ConditionalOnProperty(name = "cline.memory.semantic.enabled", havingValue = "true", matchIfMissing = true)
public class VectorMemoryConfig {

    @Bean
    public EmbeddingModel chatMemoryEmbeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> chatMemoryEmbeddingStore(
            DataSource dataSource,
            @Value("${cline.memory.semantic.table:chat_memory_embeddings}") String table,
            @Value("${cline.memory.semantic.dimension:384}") int dimension) {

        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(table)
                .dimension(dimension)
                .createTable(true)
                // skipCreateVectorExtension defaults to false → runs CREATE EXTENSION IF NOT EXISTS vector
                // Exact search (no IVFFlat index): best recall at chat-memory scale, and avoids index tuning.
                .useIndex(false)
                .build();
    }
}
