package com.aweqy.aiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 初始化向量数据库并且保存文档
 * 基金应用向量存储配置类
 */
//@Configuration
public class FundAppVectorStoreConfig {

    @Resource
    private FundAppDocumentLoader fundAppDocumentLoader;

    @Bean
    VectorStore fundAppVectorStore(EmbeddingModel openAiEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(openAiEmbeddingModel).build();
        // 加载文档
        List<Document> documentList = fundAppDocumentLoader.loadMarkdowns();
        simpleVectorStore.add(documentList);
        return simpleVectorStore;
    }
}
