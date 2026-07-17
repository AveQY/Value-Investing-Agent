package com.aweqy.aiagent.rag;

import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.rag.pgvector.enabled", havingValue = "true")
public class FundAppRagCloudAdvisorConfig {

    /**
     * 获取向量存储
     *
     * @return 向量存储
     */
    private final PgVectorStore pgVectorStore;

    public FundAppRagCloudAdvisorConfig(PgVectorStore pgVectorStore) {
        this.pgVectorStore = pgVectorStore;
    }

    @Bean
    public Advisor fundAppRagAdvisor() {
        DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(pgVectorStore)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
    }
}
