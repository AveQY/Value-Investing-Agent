package com.aweqy.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地知识库检索器：
 * 负责从本地 Markdown 文档中检索与用户问题最相关的片段，
 * 再把这些片段拼接成提示词上下文，提供给上层问答链路使用。
 */
@Component
@Slf4j
public class FundAppLocalDocumentRetriever {

    // 默认最多返回 3 条本地知识片段
    private static final int DEFAULT_LIMIT = 3;

    // 语法填充词：只表达疑问/时态，不含领域信息，命中它们不代表文档相关。
    private static final Set<String> STOP_KEYWORDS = Set.of(
            "最近", "最新", "今天", "昨天", "明天", "当前", "现在", "目前",
            "为什么", "怎么样", "怎么看", "怎么办", "什么", "多少", "哪些",
            "如何", "一下", "请问", "给我", "帮我", "讲讲", "说说", "聊聊",
            "看看", "介绍", "影响", "情况", "问题", "分析"
    );

    // Markdown 文档加载器，负责把本地知识文件读取成 Spring AI 的 Document 对象
    private final FundAppDocumentLoader fundAppDocumentLoader;

    // 已加载的本地文档缓存，首次读取后常驻内存，避免重复扫描磁盘。
    private volatile List<Document> cachedDocuments;

    // 通过构造器注入文档加载器。
    public FundAppLocalDocumentRetriever(FundAppDocumentLoader fundAppDocumentLoader) {
        this.fundAppDocumentLoader = fundAppDocumentLoader;
    }

    /**
     * 根据用户问题构建可直接塞给模型的本地知识上下文。
     */
    public String buildContext(String query) {
        // 先检索默认数量的本地资料。
        List<RetrievedDocument> documents = search(query, DEFAULT_LIMIT);
        if (documents.isEmpty()) {
            // 没有命中资料时，明确提示上层当前回答不应伪装成本地知识结论。
            return "未检索到直接相关的本地资料。请明确告知用户当前回答主要基于通用知识，而不是本地知识库。";
        }

        // 将命中的来源和片段拼成多段上下文，供模型优先参考。
        StringBuilder builder = new StringBuilder("以下内容来自本地知识库，请优先据此回答：\n");
        for (int i = 0; i < documents.size(); i++) {
            RetrievedDocument document = documents.get(i);
            builder.append(i + 1)
                    .append(". 来源：")
                    .append(document.source())
                    .append('\n')
                    .append("片段：")
                    .append(document.snippet())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    /**
     * 对外提供的检索入口：
     * 将查询词标准化、提取关键词、为每篇文档打分，然后返回最相关的若干片段。
     */
    public List<RetrievedDocument> search(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            // 空问题直接返回空结果，避免无意义检索。
            return List.of();
        }

        // 统一大小写和符号格式，降低匹配噪声。
        String normalizedQuery = normalize(query);
        // 从查询中提取关键词和 n-gram，增强中文短语命中能力。
        List<String> keywords = extractKeywords(normalizedQuery);
        return getDocuments().stream()
                // 为每篇本地文档计算相关性分数。
                .map(document -> scoreDocument(document, normalizedQuery, keywords))
                // 只保留真正命中的文档。
                .filter(scored -> scored.score() > 0)
                // 按分数从高到低排序。
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed())
                // 截取用户要求的返回条数。
                .limit(limit)
                // 转换成对上层友好的轻量结果对象。
                .map(scored -> new RetrievedDocument(
                        scored.source(),
                        buildSnippet(scored.document(), keywords),
                        scored.score()
                ))
                .toList();
    }

    /**
     * 获取本地文档缓存。
     * 这里使用双重检查，确保只在首次访问时加载一次 Markdown 文件。
     */
    private List<Document> getDocuments() {
        List<Document> local = cachedDocuments;
        if (local != null) {
            // 已经缓存过时直接返回，减少 IO 开销。
            return local;
        }
        synchronized (this) {
            if (cachedDocuments == null) {
                // 首次访问时加载全部本地 Markdown 文档并缓存。
                cachedDocuments = List.copyOf(fundAppDocumentLoader.loadMarkdowns());
                log.info("loaded {} markdown documents for local RAG", cachedDocuments.size());
            }
            return cachedDocuments;
        }
    }

    /**
     * 为单篇文档计算相关性分数。
     * 规则偏向“来源命中 + 全量查询命中 + 关键词多次命中”的组合评分。
     */
    private ScoredDocument scoreDocument(Document document, String normalizedQuery, List<String> keywords) {
        // 读取正文和来源信息，并做标准化，方便后续匹配。
        String text = defaultString(document.getText());
        String source = resolveSource(document);
        String normalizedText = normalize(text);
        String normalizedSource = normalize(source);

        int score = 0;
        if (contains(normalizedSource, normalizedQuery)) {
            // 问题直接命中文档标题/来源时，给予最高优先级。
            score += 200;
        }
        if (contains(normalizedText, normalizedQuery)) {
            // 问题整体命中文档正文时，给较高分。
            score += 160;
        }

        for (String keyword : keywords) {
            // 2 字片段和语法填充词（“为什么”“怎么样”等）碰撞噪声太大，不参与打分，
            // 只保留给摘要定位使用；结构性命中（标题/整句）不受影响。
            if (keyword.length() < 3 || STOP_KEYWORDS.contains(keyword)) {
                continue;
            }
            // 关键词越长，说明语义越完整，因此权重越高，但设置了上限避免过度放大。
            int weight = Math.min(keyword.length() * 4, 40);
            // 来源中的关键词命中更珍贵，所以在基础权重上再额外加一点。
            score += Math.min(countOccurrences(normalizedSource, keyword), 2) * (weight + 12);
            // 正文中的关键词命中只统计有限次数，防止长文档天然占优。
            score += Math.min(countOccurrences(normalizedText, keyword), 3) * weight;
        }
        return new ScoredDocument(document, source, score);
    }

    /**
     * 从标准化后的查询中提取关键词。
     * 除了原词，还会拆出 4/3/2 字符 n-gram，提升中文问题的召回率。
     */
    private List<String> extractKeywords(String normalizedQuery) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String token : normalizedQuery.split("\\s+")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (token.length() >= 2) {
                // 先保留完整 token，优先匹配更长的原始词语。
                keywords.add(token);
            }
            // 拆 4 字片段，优先兼顾完整词组和中文短语。
            addNGrams(keywords, token, 4);
            // 拆 3 字片段，增强模糊召回。
            addNGrams(keywords, token, 3);
            if (token.length() <= 8) {
                // 对较短 token 再补 2 字片段，避免过度膨胀关键词数量。
                addNGrams(keywords, token, 2);
            }
        }
        return keywords.stream()
                // 只保留至少 2 个字符的关键词，过滤掉信息量太弱的片段。
                .filter(keyword -> keyword.length() >= 2)
                // 优先使用更长的关键词，让匹配更稳定。
                .sorted(Comparator.comparingInt(String::length).reversed())
                // 控制关键词数量，避免评分时成本过高。
                .limit(30)
                .toList();
    }

    /**
     * 向关键词集合中补充固定长度的 n-gram 片段。
     */
    private void addNGrams(LinkedHashSet<String> keywords, String token, int size) {
        if (token.length() <= size) {
            // token 太短时没有必要继续切分。
            return;
        }
        for (int i = 0; i <= token.length() - size; i++) {
            keywords.add(token.substring(i, i + size));
        }
    }

    /**
     * 从文档正文中截取一个更适合展示给模型和前端的摘要片段。
     */
    private String buildSnippet(Document document, List<String> keywords) {
        // 先压平空白字符，避免片段里出现大段换行。
        String text = defaultString(document.getText()).replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(text)) {
            return "";
        }

        // 尝试找到第一个关键词命中的位置，围绕命中点截取上下文。
        int anchor = findAnchorIndex(text, keywords);
        if (anchor < 0) {
            // 如果完全找不到锚点，就退化成简单截断。
            return abbreviate(text, 420);
        }
        int start = Math.max(0, anchor - 80);
        int end = Math.min(text.length(), anchor + 360);
        String snippet = text.substring(start, end).trim();
        if (start > 0) {
            // 前面被截断时补省略号。
            snippet = "..." + snippet;
        }
        if (end < text.length()) {
            // 后面被截断时补省略号。
            snippet = snippet + "...";
        }
        return snippet;
    }

    /**
     * 在正文中找到第一个关键词出现的位置，用作摘要截取锚点。
     */
    private int findAnchorIndex(String text, List<String> keywords) {
        for (String keyword : keywords) {
            int index = text.indexOf(keyword);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 解析文档来源名称，优先用 title，其次用 filename。
     */
    private String resolveSource(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String title = asText(metadata.get("title"));
        if (StringUtils.hasText(title)) {
            return title;
        }
        String filename = asText(metadata.get("filename"));
        if (StringUtils.hasText(filename)) {
            return filename.replace(".md", "");
        }
        return "未命名文档";
    }

    /**
     * 统一字符串格式：
     * 转小写、去掉大部分标点，只保留字母和数字，便于后续 contains 匹配。
     */
    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    // 安全判断 text 是否包含 query，避免空字符串带来的误判。
    private boolean contains(String text, String query) {
        return StringUtils.hasText(text) && StringUtils.hasText(query) && text.contains(query);
    }

    /**
     * 统计关键词在文本中出现的次数，用于累计相关性分数。
     */
    private int countOccurrences(String text, String keyword) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(keyword)) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) >= 0) {
            count++;
            index += keyword.length();
        }
        return count;
    }

    // 超长文本做简单截断，避免返回过长片段。
    private String abbreviate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    // 把 null 安全地转为空字符串，减少后续判空分支。
    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    // 将任意对象安全转成文本。
    private String asText(Object value) {
        return value == null ? "" : value.toString();
    }

    // 对外暴露的检索结果：来源、片段和分数。
    public record RetrievedDocument(String source, String snippet, int score) {
    }

    // 内部评分中间对象，保存原始文档和计算后的分数。
    private record ScoredDocument(Document document, String source, int score) {
    }
}
