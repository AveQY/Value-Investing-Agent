package com.aweqy.aiagent.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundAppLocalDocumentRetrieverTest {

    private FundAppLocalDocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        FundAppDocumentLoader loader = new FundAppDocumentLoader(new PathMatchingResourcePatternResolver());
        retriever = new FundAppLocalDocumentRetriever(loader);
    }

    @Test
    void searchShouldFindValueInvestingFrameworkDocuments() {
        List<FundAppLocalDocumentRetriever.RetrievedDocument> documents =
                retriever.search("如何判断一家公司的护城河和安全边际", 5);

        assertFalse(documents.isEmpty(), "价值投资问题应命中本地知识库");

        String merged = documents.stream()
                .map(document -> document.source() + " " + document.snippet())
                .collect(Collectors.joining(" "));

        assertTrue(merged.contains("护城河"), "检索结果应包含护城河相关内容");
        assertTrue(merged.contains("安全边际") || merged.contains("价值投资"),
                "检索结果应包含安全边际或价值投资框架内容");
    }
}
