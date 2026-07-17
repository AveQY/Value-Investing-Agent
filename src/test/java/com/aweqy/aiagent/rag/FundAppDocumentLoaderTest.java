package com.aweqy.aiagent.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundAppDocumentLoaderTest {

    private FundAppDocumentLoader fundAppDocumentLoader;

    @BeforeEach
    void setUp() {
        fundAppDocumentLoader = new FundAppDocumentLoader(new PathMatchingResourcePatternResolver());
    }

    @Test
    void loadMarkdowns() {
        List<Document> documents = fundAppDocumentLoader.loadMarkdowns();

        assertFalse(documents.isEmpty(), "应至少加载到一份 Markdown 文档");
        assertTrue(documents.stream().allMatch(document ->
                        StringUtils.hasText((String) document.getMetadata().get("filename"))),
                "每个文档都应携带 filename 元数据");
        assertTrue(documents.stream().allMatch(document ->
                        StringUtils.hasText((String) document.getMetadata().get("status"))),
                "每个文档都应携带 status 元数据");
        assertTrue(documents.stream()
                        .map(document -> document.getMetadata().get("filename"))
                        .anyMatch("价值投资-思想模型.md"::equals),
                "应加载价值投资思想模型文档");
        assertTrue(documents.stream()
                        .map(document -> document.getMetadata().get("filename"))
                        .anyMatch("价值投资-分析清单.md"::equals),
                "应加载价值投资分析清单文档");
        assertTrue(documents.stream()
                        .map(document -> document.getMetadata().get("filename"))
                        .anyMatch("价值投资-边界与局限.md"::equals),
                "应加载价值投资边界与局限文档");
    }
}
