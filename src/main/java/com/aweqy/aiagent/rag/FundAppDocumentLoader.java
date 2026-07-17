package com.aweqy.aiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 文档加载器，读取目录下的 Markdown 文件并转换为 Document 对象列表
 * 这些 Document 对象将被添加到向量存储中，供 RAG
 * 系统在回答用户问题时进行检索和参考
 */
@Component
public class FundAppDocumentLoader {

    private static final String DOCUMENT_RESOURCE_PATTERN = "classpath*:document/*.md";

    private final ResourcePatternResolver resourcePatternResolver;

    public FundAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources(DOCUMENT_RESOURCE_PATTERN);
            Arrays.sort(resources, Comparator.comparing(
                    Resource::getFilename,
                    Comparator.nullsLast(String::compareTo)
            ));
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                String status = resolveStatus(filename);
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", filename)
                        .withAdditionalMetadata("status", status)
                        .build();
                MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(markdownDocumentReader.get());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Markdown 文档加载失败: " + DOCUMENT_RESOURCE_PATTERN, e);
        }
        return allDocuments;
    }

    private String resolveStatus(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "unknown";
        }
        String basename = StringUtils.stripFilenameExtension(filename);
        if (!StringUtils.hasText(basename)) {
            return "unknown";
        }
        return basename.substring(0, Math.min(3, basename.length()));
    }

}
