package com.aweqy.aiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;


/**
 * 应用向量存储配置类
 * 使用 PgVectorStore 连接 PostgreSQL 数据库中的 pgvector 插件，持久化向量数据
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.pgvector.enabled", havingValue = "true")
@Slf4j
public class PgVectorVectorStoreConfig {

    private static final String IDENTIFIER_PATTERN = "[a-zA-Z_][a-zA-Z0-9_]*";

    @Resource
    private FundAppDocumentLoader fundAppDocumentLoader;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}")
    private int dimensions;

    @Value("${spring.ai.vectorstore.pgvector.schema-name:public}")
    private String schemaName;

    @Value("${spring.ai.vectorstore.pgvector.table-name:${app.rag.vector-table-name:vector_store}}")
    private String vectorTableName;

    @Value("${spring.ai.vectorstore.pgvector.initialize-schema:true}")
    private boolean initializeSchema;

    @Value("${spring.ai.vectorstore.pgvector.max-document-batch-size:10000}")
    private int maxDocumentBatchSize;

    /**
     * 独立的向量模型客户端，直连 SiliconFlow 等支持 embedding 的供应商。
     * 与 chat 链路（spring.ai.openai / ChatModelRegistryService 自建客户端）完全解耦。
     */
    @Bean("siliconFlowEmbeddingModel")
    public EmbeddingModel siliconFlowEmbeddingModel(
            @Value("${app.rag.embedding.base-url:https://api.siliconflow.cn}") String baseUrl,
            @Value("${app.rag.embedding.api-key:}") String apiKey,
            @Value("${app.rag.embedding.model:BAAI/bge-m3}") String embeddingModel) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(60_000);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(normalizeEmbeddingBaseUrl(baseUrl))
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(embeddingModel).build());
    }

    @Bean("pgVectorEmbeddingModel")
    public EmbeddingModel pgVectorEmbeddingModel(@Qualifier("siliconFlowEmbeddingModel") EmbeddingModel embeddingModel) {
        return new ResilientEmbeddingModel(embeddingModel, dimensions);
    }

    private String normalizeEmbeddingBaseUrl(String baseUrl) {
        String normalized = StringUtils.trimWhitespace(baseUrl);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    @Bean
    @Lazy
    public PgVectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate,
                                             @Qualifier("pgVectorEmbeddingModel") EmbeddingModel embeddingModel) {
        validateIdentifiers();
        validateExistingVectorTable(jdbcTemplate);

        PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dimensions)              // Use the configured embedding dimension for the active model.
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(initializeSchema)  // Optional: defaults to false
                .schemaName(schemaName)              // Optional: defaults to "public"
                .vectorTableName(vectorTableName)    // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(maxDocumentBatchSize)
                .build();
        // Spring 要等 @Bean 方法返回后才回调 afterPropertiesSet() 执行建表 DDL，
        // 而入库必须发生在本方法内，所以先手动触发一次（DDL 均为 IF NOT EXISTS，幂等安全）。
        vectorStore.afterPropertiesSet();
        if (isVectorTableEmpty(jdbcTemplate)) {
            List<Document> documents = fundAppDocumentLoader.loadMarkdowns();
            // 分批向量化：embedding 供应商通常限制单次批量（SiliconFlow bge-m3 为 32 条），
            // 一批 16 条留出安全余量，避免一次性 85 条触发 400 后退化到本地哈希向量。
            int batchSize = 16;
            for (int start = 0; start < documents.size(); start += batchSize) {
                vectorStore.add(documents.subList(start, Math.min(start + batchSize, documents.size())));
            }
            log.info("initialized vector table {} with {} documents", vectorTableName, documents.size());
        } else {
            log.info("vector table {} already has data, skip reloading documents", vectorTableName);
        }
        return vectorStore;
    }

    private void validateIdentifiers() {
        validateIdentifier(schemaName, "schemaName");
        validateIdentifier(vectorTableName, "vectorTableName");
    }

    private void validateIdentifier(String identifier, String fieldName) {
        if (identifier == null || !identifier.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalStateException("Invalid pgvector " + fieldName + ": " + identifier);
        }
    }

    private void validateExistingVectorTable(JdbcTemplate jdbcTemplate) {
        Boolean tableExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = ?
                      AND table_name = ?
                )
                """, Boolean.class, schemaName, vectorTableName);

        if (!Boolean.TRUE.equals(tableExists)) {
            if (!initializeSchema) {
                throw new IllegalStateException(
                        "pgvector table %s.%s does not exist and initialize-schema=false"
                                .formatted(schemaName, vectorTableName)
                );
            }
            return;
        }

        String actualEmbeddingType = jdbcTemplate.query("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON a.attrelid = c.oid
                JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = ?
                  AND c.relname = ?
                  AND a.attname = 'embedding'
                  AND NOT a.attisdropped
                """, rs -> rs.next() ? rs.getString(1) : null, schemaName, vectorTableName);

        if (actualEmbeddingType == null) {
            throw new IllegalStateException(
                    "pgvector table %s.%s exists but does not contain an embedding column"
                            .formatted(schemaName, vectorTableName)
            );
        }

        String expectedEmbeddingType = "vector(" + dimensions + ")";
        if (!expectedEmbeddingType.equalsIgnoreCase(actualEmbeddingType)) {
            throw new IllegalStateException(
                    "pgvector table %s.%s uses %s, but the configured embedding dimension requires %s"
                            .formatted(schemaName, vectorTableName, actualEmbeddingType, expectedEmbeddingType)
            );
        }
    }

    private boolean isVectorTableEmpty(JdbcTemplate jdbcTemplate) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + qualifiedTableName(),
                    Integer.class
            );
            return count == null || count == 0;
        } catch (DataAccessException ex) {
            log.info("vector table {} is not ready yet, initialize it with bundled documents", vectorTableName);
            return true;
        }
    }

    private String qualifiedTableName() {
        return schemaName + "." + vectorTableName;
    }

    private static final class ResilientEmbeddingModel implements EmbeddingModel {

        private static final Logger LOGGER = LoggerFactory.getLogger(ResilientEmbeddingModel.class);

        private final EmbeddingModel delegate;

        private final int dimensions;

        private final AtomicBoolean localFallbackEnabled = new AtomicBoolean(false);

        private ResilientEmbeddingModel(EmbeddingModel delegate, int dimensions) {
            this.delegate = delegate;
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (!localFallbackEnabled.get()) {
                try {
                    return delegate.call(request);
                } catch (RuntimeException ex) {
                    if (!shouldFallback(ex)) {
                        throw ex;
                    }
                    enableLocalFallback(ex);
                }
            }
            return createLocalResponse(request.getInstructions());
        }

        @Override
        public float[] embed(Document document) {
            String text = document == null ? "" : document.getFormattedContent();
            return createLocalEmbedding(text);
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private void enableLocalFallback(RuntimeException ex) {
            if (localFallbackEnabled.compareAndSet(false, true)) {
                LOGGER.warn("embedding provider is unavailable for pgvector, falling back to local hashing embeddings: {}",
                        ex.getMessage());
            }
        }

        private boolean shouldFallback(RuntimeException ex) {
            if (!(ex instanceof NonTransientAiException)) {
                return false;
            }
            String message = ex.getMessage();
            return StringUtils.hasText(message)
                    && (message.contains("403")
                    || message.contains("400")
                    || message.contains("ModelNotAllowed")
                    || message.contains("InvalidRequestBody")
                    || message.contains("不是嵌入")
                    || message.contains("模型不允许访问"));
        }

        private EmbeddingResponse createLocalResponse(List<String> instructions) {
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(createLocalEmbedding(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        private float[] createLocalEmbedding(String text) {
            float[] vector = new float[dimensions];
            String normalized = normalize(text);
            if (!StringUtils.hasText(normalized)) {
                return vector;
            }

            String dense = normalized.replace(" ", "");
            for (String token : normalized.split("\\s+")) {
                if (!StringUtils.hasText(token)) {
                    continue;
                }
                addFeature(vector, token, 1.0f);
                addNGrams(vector, token, 2, 0.75f);
                addNGrams(vector, token, 3, 0.9f);
            }

            addNGrams(vector, dense, 2, 0.45f);
            addNGrams(vector, dense, 3, 0.6f);
            addNGrams(vector, dense, 4, 0.75f);
            normalizeVector(vector);
            return vector;
        }

        private String normalize(String text) {
            if (!StringUtils.hasText(text)) {
                return "";
            }
            return text.toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]+", " ")
                    .trim();
        }

        private void addNGrams(float[] vector, String text, int size, float weight) {
            if (!StringUtils.hasText(text) || text.length() < size) {
                return;
            }
            for (int i = 0; i <= text.length() - size; i++) {
                addFeature(vector, text.substring(i, i + size), weight);
            }
        }

        private void addFeature(float[] vector, String feature, float weight) {
            int positiveIndex = Math.floorMod(feature.hashCode(), dimensions);
            int negativeIndex = Math.floorMod(mixHash(feature), dimensions);
            vector[positiveIndex] += weight;
            vector[negativeIndex] -= weight * 0.5f;
        }

        private int mixHash(String feature) {
            int hash = feature.hashCode();
            return hash * 31 + 0x9e3779b9;
        }

        private void normalizeVector(float[] vector) {
            double squaredSum = 0.0d;
            for (float value : vector) {
                squaredSum += value * value;
            }
            if (squaredSum == 0.0d) {
                return;
            }
            float norm = (float) Math.sqrt(squaredSum);
            for (int i = 0; i < vector.length; i++) {
                vector[i] = vector[i] / norm;
            }
        }
    }
}
