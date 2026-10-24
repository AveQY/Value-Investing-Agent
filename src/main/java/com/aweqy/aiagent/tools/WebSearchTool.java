package com.aweqy.aiagent.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class WebSearchTool {

    private static final String SOGOU_SEARCH_URL = "https://www.sogou.com/web?query=%s";
    private static final String DUCKDUCKGO_HTML_SEARCH_URL = "https://html.duckduckgo.com/html/";
    private static final String BING_SEARCH_URL = "https://www.bing.com/search?q=%s&setlang=zh-cn";
    private static final String SEARCH_REFERER = "https://duckduckgo.com/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";
    private static final int SEARCH_TIMEOUT_MS = 15_000;
    private static final int PAGE_TIMEOUT_MS = 12_000;
    private static final int MAX_RESULTS = 6;
    private static final int MAX_FETCHED_PAGES = 3;
    private static final int MAX_RAW_CONTENT_LENGTH = 40_000;
    private static final int MAX_SNIPPET_LENGTH = 800;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 免费多搜索引擎聚合搜索，并尝试抓取网页正文。
     */
    @Tool(name = "Search for information")
    public String searchInformation(
            @ToolParam(description = "Search query keyword") String query) {
        long startTime = System.currentTimeMillis();

        if (!StringUtils.hasText(query)) {
            return errorJson("请输入搜索关键词");
        }

        try {
            List<SearchResult> searchResults = search(query.trim());
            JSONArray formattedResults = new JSONArray();
            List<JSONObject> answerSources = new ArrayList<>();
            int fetchedPages = 0;

            for (int index = 0; index < searchResults.size(); index++) {
                SearchResult result = searchResults.get(index);
                PageSnapshot pageSnapshot = PageSnapshot.empty();
                if (fetchedPages < MAX_FETCHED_PAGES && isFetchableWebPage(result.url())) {
                    pageSnapshot = fetchPageSnapshot(result.url());
                    if (pageSnapshot.hasContent()) {
                        fetchedPages++;
                    }
                }

                JSONObject item = new JSONObject();
                String displayUrl = firstNonBlank(pageSnapshot.resolvedUrl(), result.url());
                item.set("title", firstNonBlank(pageSnapshot.title(), result.title()));
                item.set("url", displayUrl);
                item.set("content", firstNonBlank(
                        result.snippet(),
                        pageSnapshot.summary(),
                        previewText(pageSnapshot.rawContent(), MAX_SNIPPET_LENGTH)
                ));
                item.set("score", Math.max(0.1, 1.0 - (index * 0.08)));
                item.set("raw_content", pageSnapshot.rawContent());
                item.set("raw_content_truncated", pageSnapshot.truncated());
                item.set("raw_content_original_length", pageSnapshot.originalLength());
                item.set("favicon", firstNonBlank(result.favicon(), faviconFromUrl(displayUrl)));
                item.set("engine", result.engine());
                formattedResults.add(item);
                answerSources.add(item);
            }

            JSONObject formatted = new JSONObject();
            formatted.set("query", query);
            formatted.set("answer", buildAnswer(query, answerSources));
            formatted.set("images", new JSONArray());
            formatted.set("results", formattedResults);
            formatted.set("response_time", (System.currentTimeMillis() - startTime) / 1000.0);

            JSONObject autoParameters = new JSONObject();
            autoParameters.set("engine", "multi-search-html");
            autoParameters.set("engines", List.of("sogou-html", "bing-html", "duckduckgo-html"));
            autoParameters.set("free_search", true);
            autoParameters.set("page_fetch_enabled", true);
            autoParameters.set("fetched_pages", fetchedPages);
            formatted.set("auto_parameters", autoParameters);

            JSONObject usage = new JSONObject();
            usage.set("search_result_count", formattedResults.size());
            usage.set("fetched_page_count", fetchedPages);
            usage.set("max_results", MAX_RESULTS);
            formatted.set("usage", usage);
            formatted.set("request_id", UUID.randomUUID().toString());

            return JSONUtil.toJsonPrettyStr(formatted);
        } catch (Exception e) {
            return errorJson("Search failed: " + e.getMessage());
        }
    }

    private List<SearchResult> search(String query) {
        List<SearchResult> mergedResults = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        if (containsChinese(query)) {
            mergeSearchResults(mergedResults, seenUrls, searchWithSogou(query));
            mergeSearchResults(mergedResults, seenUrls, searchWithBing(query));
            mergeSearchResults(mergedResults, seenUrls, searchWithDuckDuckGo(query));
        } else {
            mergeSearchResults(mergedResults, seenUrls, searchWithBing(query));
            mergeSearchResults(mergedResults, seenUrls, searchWithDuckDuckGo(query));
            mergeSearchResults(mergedResults, seenUrls, searchWithSogou(query));
        }
        return mergedResults.stream().limit(MAX_RESULTS).toList();
    }

    private void mergeSearchResults(List<SearchResult> mergedResults,
                                    Set<String> seenUrls,
                                    List<SearchResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (SearchResult candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.url()) || !seenUrls.add(candidate.url())) {
                continue;
            }
            mergedResults.add(candidate);
            if (mergedResults.size() >= MAX_RESULTS) {
                return;
            }
        }
    }

    private List<SearchResult> searchWithSogou(String query) {
        String searchUrl = SOGOU_SEARCH_URL.formatted(URLEncoder.encode(buildSearchQuery(query), StandardCharsets.UTF_8));
        String html = fetchBody(searchUrl, "https://www.sogou.com/", SEARCH_TIMEOUT_MS);
        if (!StringUtils.hasText(html)) {
            return List.of();
        }

        Document document = Jsoup.parse(html, "https://www.sogou.com/");
        Elements titleElements = document.select("h3 a");
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (Element titleElement : titleElements) {
            SearchResult result = parseSogouResult(titleElement);
            if (result == null
                    || !StringUtils.hasText(result.url())
                    || !isRelevantResult(query, result)
                    || !seenUrls.add(result.url())) {
                continue;
            }
            results.add(result);
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    private List<SearchResult> searchWithDuckDuckGo(String query) {
        String encodedQuery = URLEncoder.encode(buildSearchQuery(query), StandardCharsets.UTF_8);
        String html = fetchBody(DUCKDUCKGO_HTML_SEARCH_URL + "?q=" + encodedQuery + "&kl=cn-zh", SEARCH_REFERER, SEARCH_TIMEOUT_MS);
        if (!StringUtils.hasText(html)) {
            return List.of();
        }

        Document document = Jsoup.parse(html, DUCKDUCKGO_HTML_SEARCH_URL);

        Elements resultElements = document.select("div.result");
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (Element resultElement : resultElements) {
            SearchResult result = parseDuckDuckGoResult(resultElement);
            if (result == null
                    || !StringUtils.hasText(result.url())
                    || !isRelevantResult(query, result)
                    || !seenUrls.add(result.url())) {
                continue;
            }
            results.add(result);
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    private List<SearchResult> searchWithBing(String query) {
        String searchUrl = BING_SEARCH_URL.formatted(URLEncoder.encode(buildSearchQuery(query), StandardCharsets.UTF_8));
        String html = fetchBody(searchUrl, "https://www.bing.com/", SEARCH_TIMEOUT_MS);
        if (!StringUtils.hasText(html)) {
            return List.of();
        }

        Document document = Jsoup.parse(html, searchUrl);
        Elements resultElements = document.select("li.b_algo");
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (Element resultElement : resultElements) {
            SearchResult result = parseBingResult(resultElement);
            if (result == null
                    || !StringUtils.hasText(result.url())
                    || !isRelevantResult(query, result)
                    || !seenUrls.add(result.url())) {
                continue;
            }
            results.add(result);
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    private SearchResult parseDuckDuckGoResult(Element resultElement) {
        if (resultElement == null) {
            return null;
        }

        Element titleElement = resultElement.selectFirst("a.result__a");
        if (titleElement == null) {
            return null;
        }

        String url = extractActualUrl(titleElement.attr("href"));
        if (!StringUtils.hasText(url)) {
            return null;
        }

        Element snippetElement = resultElement.selectFirst(".result__snippet");
        Element faviconElement = resultElement.selectFirst(".result__icon__img");
        return new SearchResult(
                normalizeText(titleElement.text()),
                url,
                normalizeText(snippetElement == null ? "" : snippetElement.text()),
                absoluteUrl(faviconElement == null ? "" : faviconElement.attr("src")),
                "duckduckgo-html"
        );
    }

    private SearchResult parseSogouResult(Element titleElement) {
        if (titleElement == null) {
            return null;
        }

        String url = resolveUrl(titleElement.attr("href"), "https://www.sogou.com");
        if (!StringUtils.hasText(url)) {
            return null;
        }

        String title = normalizeText(titleElement.text());
        String snippet = "";
        Element container = titleElement.parent();
        for (int depth = 0; depth < 5 && container != null; depth++) {
            String text = normalizeText(container.text());
            if (text.length() > title.length() + 16) {
                snippet = text.replace(title, "").trim();
                break;
            }
            container = container.parent();
        }

        return new SearchResult(
                title,
                url,
                snippet,
                faviconFromUrl(url),
                "sogou-html"
        );
    }

    private SearchResult parseBingResult(Element resultElement) {
        if (resultElement == null) {
            return null;
        }

        Element titleElement = resultElement.selectFirst("h2 a");
        if (titleElement == null) {
            return null;
        }

        String url = absoluteUrl(titleElement.attr("href"));
        if (!StringUtils.hasText(url) || url.contains("bing.com/ck/")) {
            return null;
        }

        Element snippetElement = resultElement.selectFirst(".b_caption p");
        return new SearchResult(
                normalizeText(titleElement.text()),
                url,
                normalizeText(snippetElement == null ? "" : snippetElement.text()),
                faviconFromUrl(url),
                "bing-html"
        );
    }

    private PageSnapshot fetchPageSnapshot(String url) {
        try {
            HttpFetchResponse response = fetchResponse(url, SEARCH_REFERER, PAGE_TIMEOUT_MS);
            String contentType = response.contentType();
            if (StringUtils.hasText(contentType) && !contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
                return PageSnapshot.empty();
            }

            String body = response.body();
            if (!StringUtils.hasText(body)) {
                return PageSnapshot.empty();
            }

            Document document = Jsoup.parse(body, url);
            document.select("script, style, noscript, svg, canvas, iframe, header, footer, nav, form, aside").remove();

            Element primaryContent = extractPrimaryContent(document);
            String rawText = normalizeText(primaryContent == null ? "" : primaryContent.text());
            if (!StringUtils.hasText(rawText) && document.body() != null) {
                rawText = normalizeText(document.body().text());
            }

            String title = normalizeText(firstNonBlank(
                    document.title(),
                    document.select("meta[property=og:title]").attr("content")
            ));
            String summary = normalizeText(firstNonBlank(
                    document.select("meta[name=description]").attr("content"),
                    document.select("meta[property=og:description]").attr("content"),
                    previewText(rawText, MAX_SNIPPET_LENGTH)
            ));

            boolean truncated = rawText.length() > MAX_RAW_CONTENT_LENGTH;
            String safeRawText = truncated ? rawText.substring(0, MAX_RAW_CONTENT_LENGTH).trim() : rawText;
            return new PageSnapshot(title, summary, safeRawText, rawText.length(), truncated, resolveFinalUrl(url, response.finalUrl()));
        } catch (Exception ignored) {
            return PageSnapshot.empty();
        }
    }

    private String resolveFinalUrl(String requestedUrl, String fetchedUrl) {
        if (!StringUtils.hasText(fetchedUrl) || !fetchedUrl.startsWith("http")) {
            return "";
        }
        // 请求地址与最终地址不同说明发生了跳转（如搜狗 /link?url= 中转），优先展示真实来源。
        return fetchedUrl.equals(requestedUrl) ? "" : fetchedUrl;
    }

    private String buildSearchQuery(String query) {
        // 不再给搜索词追加“股票/财经”等泛化后缀：规划器给出的查询词已贴合用户问题，
        // 追加后缀会把结果带偏到泛财经门户页，降低命中率。
        return normalizeText(query);
    }

    private boolean isRelevantResult(String query, SearchResult result) {
        if (result == null) {
            return false;
        }

        String haystack = (firstNonBlank(result.title(), "") + " "
                + firstNonBlank(result.snippet(), "") + " "
                + firstNonBlank(result.url(), "")).toLowerCase(Locale.ROOT);

        List<String> tokens = extractQueryTokens(query);
        if (tokens.isEmpty()) {
            return true;
        }

        int matches = 0;
        for (String token : tokens) {
            if (haystack.contains(token.toLowerCase(Locale.ROOT))) {
                matches++;
            }
        }
        return matches >= Math.min(2, tokens.size()) || matches >= 1 && tokens.size() == 1;
    }

    private List<String> extractQueryTokens(String query) {
        String normalized = normalizeText(query);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("[\\s,，。;；:：/\\\\()（）\\[\\]{}<>]+")) {
            String trimmed = token.trim();
            if (!StringUtils.hasText(trimmed) || trimmed.length() < 2 || isStopToken(trimmed)) {
                continue;
            }
            tokens.add(trimmed);
        }

        if (tokens.isEmpty() && normalized.length() >= 2) {
            tokens.add(normalized);
        }
        return new ArrayList<>(tokens);
    }

    private boolean isStopToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "最近", "最新", "今天", "当前", "如何", "什么", "多少", "一下", "分析", "请问",
                "time", "date", "what", "when", "how", "why", "latest");
    }

    private String fetchBody(String url, String referer, int timeoutMs) {
        return fetchResponse(url, referer, timeoutMs).body();
    }

    private HttpFetchResponse fetchResponse(String url, String referer, int timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            // response.uri() 是跟随重定向后的最终地址，可用于把搜狗 /link?url= 跳转链还原成真实来源。
            return new HttpFetchResponse(response.body(), contentType, String.valueOf(response.uri()));
        } catch (Exception ignored) {
            return HttpFetchResponse.empty();
        }
    }

    private Element extractPrimaryContent(Document document) {
        if (document == null) {
            return null;
        }

        List<String> selectors = List.of(
                "main",
                "article",
                "[role=main]",
                ".article",
                ".article-content",
                ".entry-content",
                ".post-content",
                ".rich_media_content",
                "#content",
                ".content"
        );

        for (String selector : selectors) {
            Element element = document.selectFirst(selector);
            if (element != null && normalizeText(element.text()).length() >= 120) {
                return element;
            }
        }
        return document.body();
    }

    private boolean isFetchableWebPage(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        return !lowerUrl.endsWith(".pdf")
                && !lowerUrl.endsWith(".doc")
                && !lowerUrl.endsWith(".docx")
                && !lowerUrl.endsWith(".xls")
                && !lowerUrl.endsWith(".xlsx")
                && !lowerUrl.endsWith(".zip")
                && !lowerUrl.endsWith(".rar");
    }

    private String extractActualUrl(String href) {
        String absoluteHref = absoluteUrl(href);
        if (!StringUtils.hasText(absoluteHref)) {
            return "";
        }

        try {
            URI uri = URI.create(absoluteHref);
            String host = uri.getHost();
            if (host != null && host.contains("duckduckgo.com")) {
                String decoded = queryParam(uri.getRawQuery(), "uddg");
                if (StringUtils.hasText(decoded)) {
                    return decoded;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return absoluteHref;
    }

    private String queryParam(String rawQuery, String targetName) {
        if (!StringUtils.hasText(rawQuery) || !StringUtils.hasText(targetName)) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!targetName.equals(name)) {
                continue;
            }
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return "";
    }

    private String absoluteUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return "";
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("/")) {
            return "https://duckduckgo.com" + trimmed;
        }
        return trimmed;
    }

    private String resolveUrl(String rawUrl, String baseUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return "";
        }

        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("/")) {
            return firstNonBlank(baseUrl, "") + trimmed;
        }
        return trimmed;
    }

    private String buildAnswer(String query, List<JSONObject> results) {
        if (results == null || results.isEmpty()) {
            return "未检索到与“" + query + "”直接相关的公开网页结果。";
        }

        List<String> summaryLines = new ArrayList<>();
        for (int index = 0; index < Math.min(3, results.size()); index++) {
            JSONObject result = results.get(index);
            String title = normalizeText(result.getStr("title", ""));
            String content = normalizeText(result.getStr("content", ""));
            if (!StringUtils.hasText(content)) {
                content = previewText(result.getStr("raw_content", ""), MAX_SNIPPET_LENGTH);
            }
            if (StringUtils.hasText(title) || StringUtils.hasText(content)) {
                summaryLines.add((index + 1) + ". " + firstNonBlank(title, "未命名来源") + "："
                        + firstNonBlank(content, "抓取到链接，但正文较少。"));
            }
        }

        if (summaryLines.isEmpty()) {
            return "已检索到公开网页结果，但暂未提取到足够稳定的文本摘要。";
        }
        return "以下为免费搜索与网页抓取得到的公开信息摘要：\n" + String.join("\n", summaryLines);
    }

    private String previewText(String value, int maxLength) {
        String normalized = normalizeText(value);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength).trim();
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String text, String... candidates) {
        String normalized = String.valueOf(text);
        for (String candidate : candidates) {
            if (normalized.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsChinese(String text) {
        return StringUtils.hasText(text) && text.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String faviconFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme() : "https";
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return "";
            }
            return scheme + "://" + host + "/favicon.ico";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String errorJson(String message) {
        JSONObject error = new JSONObject();
        error.set("query", "ERROR");
        error.set("answer", message);
        error.set("images", new JSONArray());
        error.set("results", new JSONArray());
        error.set("response_time", 0.0);
        return JSONUtil.toJsonPrettyStr(error);
    }

    private record SearchResult(String title,
                                String url,
                                String snippet,
                                String favicon,
                                String engine) {
    }

    private record HttpFetchResponse(String body, String contentType, String finalUrl) {

        private static HttpFetchResponse empty() {
            return new HttpFetchResponse("", "", "");
        }
    }

    private record PageSnapshot(String title,
                                String summary,
                                String rawContent,
                                int originalLength,
                                boolean truncated,
                                String resolvedUrl) {

        private static PageSnapshot empty() {
            return new PageSnapshot("", "", null, 0, false, "");
        }

        private boolean hasContent() {
            return StringUtils.hasText(summary) || StringUtils.hasText(rawContent);
        }
    }
}
