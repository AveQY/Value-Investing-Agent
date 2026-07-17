package com.aweqy.aiagent.tools;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实时股价查询工具，支持 A 股、港股和美股。
 */
public class StockPriceTool {

    private static final String SINA_SUGGEST_URL_TEMPLATE =
            "https://suggest3.sinajs.cn/suggest/type=11,31,41&key=%s&name=suggestdata";
    private static final String SINA_QUOTE_URL_TEMPLATE = "https://hq.sinajs.cn/list=%s";

    private static final Pattern MARKET_SYMBOL_PATTERN = Pattern.compile(
            "(?i)\\b(?:sh|sz|bj)\\d{6}\\b|\\bhk\\d{5}\\b|\\bgb_[A-Z][A-Z0-9\\.-]{0,9}\\b");
    private static final Pattern CODE_WITH_EXCHANGE_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{5,6})\\.(SH|SZ|BJ|HK|US)\\b");
    private static final Pattern NUMBER_CODE_PATTERN = Pattern.compile("\\b\\d{5,6}\\b");
    private static final Pattern US_TICKER_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9\\.-]{0,9}\\b");

    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "ADR", "ETF", "HK", "US", "NOW", "PRICE", "LATEST", "REALTIME",
            "CURRENT", "QUOTE", "TODAY", "WHAT", "THE", "IS", "OF", "FOR",
            "CHECK", "PLEASE", "STOCK", "SHARE", "LOOK", "TELL", "ME"
    );

    @Tool(
            name = "Get realtime stock quote",
            description = "Query the latest public stock quote for one stock at a time. " +
                    "Supports A-shares, Hong Kong stocks and US stocks. " +
                    "The input can be a stock code, ticker, company name, or a short question like " +
                    "'贵州茅台股价', '00700', 'AAPL', or '腾讯现在股价多少'."
    )
    public String getRealtimeStockQuote(
            @ToolParam(description = "Stock code, ticker, company name, or a short question containing the target stock")
            String stockQuery) {

        String lookupInput = extractLookupInput(stockQuery);
        if (!StringUtils.hasText(lookupInput)) {
            return errorJson(stockQuery, "请输入股票代码、股票名称，或包含股票名称的简短问题。");
        }

        try {
            ResolvedStock resolvedStock = resolveStock(stockQuery, lookupInput);
            if (resolvedStock == null) {
                return errorJson(stockQuery, "未找到匹配的股票，请提供更准确的股票代码或名称，例如 600519、00700、AAPL。");
            }

            String quoteResponse = fetchQuoteResponse(resolvedStock.sinaSymbol());
            JSONObject quote = parseQuoteResponse(resolvedStock, quoteResponse);
            quote.set("status", "SUCCESS");
            quote.set("input", stockQuery);
            quote.set("lookupInput", lookupInput);
            quote.set("symbol", resolvedStock.code());
            quote.set("market", resolvedStock.market().displayName());
            quote.set("currency", resolvedStock.market().currency());
            quote.set("sourceSymbol", resolvedStock.sinaSymbol());
            quote.set("source", "新浪财经");
            quote.set("note", "行情为公开行情接口返回结果，请以交易所或券商终端为准。");
            return JSONUtil.toJsonPrettyStr(quote);
        } catch (Exception ex) {
            return errorJson(stockQuery, "实时股价查询失败：" + ex.getMessage());
        }
    }

    String extractLookupInput(String stockQuery) {
        String trimmed = stockQuery == null ? "" : stockQuery.trim();
        if (!StringUtils.hasText(trimmed)) {
            return "";
        }

        Matcher directSymbolMatcher = MARKET_SYMBOL_PATTERN.matcher(trimmed);
        if (directSymbolMatcher.find()) {
            return directSymbolMatcher.group();
        }

        Matcher exchangeMatcher = CODE_WITH_EXCHANGE_PATTERN.matcher(trimmed.toUpperCase(Locale.ROOT));
        if (exchangeMatcher.find()) {
            return exchangeMatcher.group();
        }

        Matcher numberCodeMatcher = NUMBER_CODE_PATTERN.matcher(trimmed);
        if (numberCodeMatcher.find()) {
            return numberCodeMatcher.group();
        }

        Matcher tickerMatcher = US_TICKER_PATTERN.matcher(trimmed.toUpperCase(Locale.ROOT));
        while (tickerMatcher.find()) {
            String token = tickerMatcher.group();
            if (!ENGLISH_STOP_WORDS.contains(token)) {
                return token;
            }
        }

        String cleaned = trimmed
                .replaceAll("[，。！？、：；（）【】《》“”‘’,.!?;:()\\[\\]{}<>\"']", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|麻烦|查一下|查下|查询|搜一下|搜下|看一下|看下|想看|想查|帮忙|现在|实时|最新|当前|今日|今天|股价|价格|多少钱|多少|是多少|行情|走势|报价|现价|盘中|收盘价|呢|吗|股票|港股|美股|a股|ADR|adr|一下)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return StringUtils.hasText(cleaned) ? cleaned : trimmed;
    }

    ResolvedStock resolveDirectSymbol(String lookupInput) {
        if (!StringUtils.hasText(lookupInput)) {
            return null;
        }

        String normalized = lookupInput.trim().toUpperCase(Locale.ROOT);

        Matcher directMatcher = Pattern.compile("^(SH|SZ|BJ)(\\d{6})$").matcher(normalized);
        if (directMatcher.matches()) {
            Market market = marketFromExchangePrefix(directMatcher.group(1));
            return new ResolvedStock(directMatcher.group(2), null, market, normalized.toLowerCase(Locale.ROOT), false);
        }

        directMatcher = Pattern.compile("^(HK)(\\d{5})$").matcher(normalized);
        if (directMatcher.matches()) {
            return new ResolvedStock(directMatcher.group(2), null, Market.HK,
                    "hk" + directMatcher.group(2), false);
        }

        directMatcher = Pattern.compile("^(GB_)([A-Z][A-Z0-9\\.-]{0,9})$").matcher(normalized);
        if (directMatcher.matches()) {
            return new ResolvedStock(directMatcher.group(2), null, Market.US,
                    "gb_" + directMatcher.group(2).toLowerCase(Locale.ROOT), false);
        }

        directMatcher = Pattern.compile("^([A-Z][A-Z0-9\\.-]{0,9})\\.US$").matcher(normalized);
        if (directMatcher.matches()) {
            return new ResolvedStock(directMatcher.group(1), null, Market.US,
                    "gb_" + directMatcher.group(1).toLowerCase(Locale.ROOT), false);
        }

        Matcher codeWithExchangeMatcher = Pattern.compile("^(\\d{5,6})\\.(SH|SZ|BJ|HK|US)$").matcher(normalized);
        if (codeWithExchangeMatcher.matches()) {
            String code = codeWithExchangeMatcher.group(1);
            String exchange = codeWithExchangeMatcher.group(2);
            if ("HK".equals(exchange)) {
                return new ResolvedStock(leftPad(code, 5), null, Market.HK, "hk" + leftPad(code, 5), false);
            }
            if ("US".equals(exchange)) {
                return new ResolvedStock(code.toUpperCase(Locale.ROOT), null, Market.US,
                        "gb_" + code.toLowerCase(Locale.ROOT), false);
            }
            Market market = marketFromExchangePrefix(exchange);
            return new ResolvedStock(code, null, market, exchange.toLowerCase(Locale.ROOT) + code, false);
        }

        Matcher numericMatcher = Pattern.compile("^\\d{6}$").matcher(normalized);
        if (numericMatcher.matches()) {
            String exchangePrefix = inferCnExchangePrefix(normalized);
            if (exchangePrefix != null) {
                return new ResolvedStock(normalized, null, marketFromExchangePrefix(exchangePrefix),
                        exchangePrefix + normalized, false);
            }
        }

        numericMatcher = Pattern.compile("^\\d{5}$").matcher(normalized);
        if (numericMatcher.matches()) {
            return new ResolvedStock(normalized, null, Market.HK, "hk" + normalized, false);
        }

        Matcher usTickerMatcher = Pattern.compile("^[A-Z][A-Z0-9\\.-]{0,9}$").matcher(normalized);
        if (usTickerMatcher.matches() && !ENGLISH_STOP_WORDS.contains(normalized)) {
            return new ResolvedStock(normalized, null, Market.US, "gb_" + normalized.toLowerCase(Locale.ROOT), false);
        }

        return null;
    }

    List<ResolvedStock> parseSuggestResponse(String suggestResponse) {
        String payload = extractQuotedPayload(suggestResponse);
        if (!StringUtils.hasText(payload)) {
            return List.of();
        }

        String[] rows = payload.split(";");
        List<ResolvedStock> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String row : rows) {
            if (!StringUtils.hasText(row)) {
                continue;
            }

            String[] fields = row.split(",", -1);
            if (fields.length < 5) {
                continue;
            }

            Market market = marketFromSuggestType(fields[1]);
            if (market == null) {
                continue;
            }

            String code = normalizeCandidateCode(fields[2], market);
            String name = firstNonBlank(fields[4], fields[6], fields[0], fields[2]);
            String sinaSymbol = buildSinaSymbol(market, code, fields[3]);
            if (!StringUtils.hasText(code) || !StringUtils.hasText(sinaSymbol)) {
                continue;
            }

            boolean adr = row.toUpperCase(Locale.ROOT).contains("ADR");
            String uniqueKey = market.name() + ":" + code;
            if (seen.add(uniqueKey)) {
                candidates.add(new ResolvedStock(code, name, market, sinaSymbol, adr));
            }
        }

        return candidates;
    }

    JSONObject parseQuoteResponse(ResolvedStock resolvedStock, String quoteResponse) {
        String payload = extractQuotedPayload(quoteResponse);
        if (!StringUtils.hasText(payload)) {
            throw new IllegalStateException("行情接口返回为空，可能是股票代码无法识别。");
        }

        String[] parts = payload.split(",", -1);
        return switch (resolvedStock.market()) {
            case CN -> parseCnQuote(parts);
            case HK -> parseHkQuote(parts);
            case US -> parseUsQuote(parts);
        };
    }

    private ResolvedStock resolveStock(String stockQuery, String lookupInput) {
        ResolvedStock directMatch = resolveDirectSymbol(lookupInput);
        if (directMatch != null) {
            return directMatch;
        }

        List<ResolvedStock> candidates = searchCandidates(lookupInput);
        if (candidates.isEmpty() && !lookupInput.equals(stockQuery == null ? "" : stockQuery.trim())) {
            candidates = searchCandidates(stockQuery == null ? "" : stockQuery.trim());
        }
        if (candidates.isEmpty()) {
            return null;
        }

        MarketHint marketHint = detectMarketHint(stockQuery);
        String normalizedLookup = normalizeText(lookupInput);
        boolean chineseQuery = containsChinese(lookupInput);
        boolean tickerLike = isTickerLike(lookupInput) || lookupInput.matches("\\d{5,6}");

        return candidates.stream()
                .max(Comparator.comparingInt(candidate ->
                        scoreCandidate(candidate, normalizedLookup, marketHint, chineseQuery, tickerLike)))
                .orElse(null);
    }

    private List<ResolvedStock> searchCandidates(String lookupInput) {
        if (!StringUtils.hasText(lookupInput)) {
            return List.of();
        }

        String encodedKeyword = URLEncoder.encode(lookupInput, StandardCharsets.UTF_8);
        String url = SINA_SUGGEST_URL_TEMPLATE.formatted(encodedKeyword);
        String response = HttpRequest.get(url)
                .header("Referer", "https://finance.sina.com.cn")
                .header("User-Agent", "Mozilla/5.0")
                .charset(CharsetUtil.CHARSET_GBK)
                .timeout(10_000)
                .execute()
                .body();
        return parseSuggestResponse(response);
    }

    private String fetchQuoteResponse(String sinaSymbol) {
        String url = SINA_QUOTE_URL_TEMPLATE.formatted(sinaSymbol);
        return HttpRequest.get(url)
                .header("Referer", "https://finance.sina.com.cn")
                .header("User-Agent", "Mozilla/5.0")
                .charset(CharsetUtil.CHARSET_GBK)
                .timeout(10_000)
                .execute()
                .body();
    }

    private JSONObject parseCnQuote(String[] parts) {
        if (parts.length < 32) {
            throw new IllegalStateException("A 股行情返回格式异常。");
        }

        BigDecimal open = decimal(parts[1]);
        BigDecimal previousClose = decimal(parts[2]);
        BigDecimal price = decimal(parts[3]);

        JSONObject result = baseQuote(
                firstNonBlank(parts[0], "未知标的"),
                price,
                previousClose,
                open,
                decimal(parts[4]),
                decimal(parts[5]),
                composeTime(parts[30], parts[31]),
                "盘中/收盘快照"
        );
        result.set("change", subtract(price, previousClose));
        result.set("changePercent", percentageChange(price, previousClose));
        result.set("bid", decimal(parts[6]));
        result.set("ask", decimal(parts[7]));
        result.set("volume", decimal(parts[8]));
        result.set("turnover", decimal(parts[9]));
        return result;
    }

    private JSONObject parseHkQuote(String[] parts) {
        if (parts.length < 19) {
            throw new IllegalStateException("港股行情返回格式异常。");
        }

        JSONObject result = baseQuote(
                firstNonBlank(parts[1], parts[0], "未知标的"),
                decimal(parts[6]),
                decimal(parts[3]),
                decimal(parts[2]),
                decimal(parts[4]),
                decimal(parts[5]),
                composeTime(parts[17], parts[18]),
                "盘中/收盘快照"
        );
        result.set("englishName", blankToNull(parts[0]));
        result.set("change", decimal(parts[7]));
        result.set("changePercent", normalizeDecimal(decimal(parts[8])));
        result.set("bid", decimal(parts[9]));
        result.set("ask", decimal(parts[10]));
        result.set("turnover", decimal(parts[11]));
        result.set("volume", decimal(parts[12]));
        result.set("high52Week", decimal(parts[15]));
        result.set("low52Week", decimal(parts[16]));
        return result;
    }

    private JSONObject parseUsQuote(String[] parts) {
        if (parts.length < 14) {
            throw new IllegalStateException("美股行情返回格式异常。");
        }

        BigDecimal price = decimal(parts[1]);
        BigDecimal change = decimal(parts[4]);
        BigDecimal previousClose = parts.length > 26 ? decimal(parts[26]) : subtract(price, change);

        JSONObject result = baseQuote(
                firstNonBlank(parts[0], "未知标的"),
                price,
                previousClose,
                decimal(parts[5]),
                decimal(parts[6]),
                decimal(parts[7]),
                blankToNull(parts[3]),
                "盘中/延时快照"
        );
        result.set("change", normalizeDecimal(change));
        result.set("changePercent", normalizeDecimal(decimal(parts[2])));
        result.set("high52Week", decimal(parts[8]));
        result.set("low52Week", decimal(parts[9]));
        result.set("volume", decimal(parts[10]));
        result.set("averageVolume", decimal(parts[11]));
        result.set("marketCap", decimal(parts[12]));
        result.set("peRatio", decimal(parts[13]));
        return result;
    }

    private JSONObject baseQuote(String name,
                                 BigDecimal price,
                                 BigDecimal previousClose,
                                 BigDecimal open,
                                 BigDecimal high,
                                 BigDecimal low,
                                 String quoteTime,
                                 String quoteType) {
        JSONObject result = new JSONObject();
        result.set("name", name);
        result.set("price", normalizeDecimal(price));
        result.set("previousClose", normalizeDecimal(previousClose));
        result.set("open", normalizeDecimal(open));
        result.set("high", normalizeDecimal(high));
        result.set("low", normalizeDecimal(low));
        result.set("quoteTime", quoteTime);
        result.set("quoteType", quoteType);
        return result;
    }

    private int scoreCandidate(ResolvedStock candidate,
                               String normalizedLookup,
                               MarketHint marketHint,
                               boolean chineseQuery,
                               boolean tickerLike) {
        int score = 0;
        String normalizedName = normalizeText(candidate.name());
        String normalizedCode = candidate.code().toUpperCase(Locale.ROOT);
        String normalizedSourceSymbol = candidate.sinaSymbol().toUpperCase(Locale.ROOT);

        if (normalizedCode.equals(normalizedLookup)) {
            score += 220;
        }
        if (normalizedName.equals(normalizedLookup)) {
            score += 160;
        }
        if (normalizedName.contains(normalizedLookup) || normalizedLookup.contains(normalizedName)) {
            score += 80;
        }
        if (normalizedSourceSymbol.equals(normalizedLookup)
                || normalizedSourceSymbol.equals("GB_" + normalizedLookup)) {
            score += 120;
        }

        score += marketPreferenceScore(candidate.market(), marketHint, chineseQuery, tickerLike);
        if (candidate.adr() && marketHint != MarketHint.US) {
            score -= 40;
        }
        if (candidate.market() == Market.US && isTickerLike(normalizedLookup)) {
            score += 40;
        }
        if (candidate.market() == Market.HK && normalizedLookup.matches("\\d{5}")) {
            score += 40;
        }
        if (candidate.market() == Market.CN && normalizedLookup.matches("\\d{6}")) {
            score += 40;
        }
        return score;
    }

    private int marketPreferenceScore(Market market,
                                      MarketHint marketHint,
                                      boolean chineseQuery,
                                      boolean tickerLike) {
        if (marketHint == MarketHint.CN) {
            return market == Market.CN ? 140 : 0;
        }
        if (marketHint == MarketHint.HK) {
            return market == Market.HK ? 140 : 0;
        }
        if (marketHint == MarketHint.US) {
            return market == Market.US ? 140 : 0;
        }

        if (chineseQuery) {
            return switch (market) {
                case CN -> 100;
                case HK -> 80;
                case US -> 20;
            };
        }

        if (tickerLike) {
            return switch (market) {
                case US -> 100;
                case HK -> 40;
                case CN -> 30;
            };
        }

        return switch (market) {
            case CN -> 80;
            case HK -> 60;
            case US -> 40;
        };
    }

    private MarketHint detectMarketHint(String stockQuery) {
        String normalized = stockQuery == null ? "" : stockQuery.toLowerCase(Locale.ROOT);
        if (normalized.contains("港股") || normalized.contains(".hk")) {
            return MarketHint.HK;
        }
        if (normalized.contains("美股")
                || normalized.contains(".us")
                || normalized.contains("nasdaq")
                || normalized.contains("nyse")
                || normalized.contains("adr")) {
            return MarketHint.US;
        }
        if (normalized.contains("a股")
                || normalized.contains(".sh")
                || normalized.contains(".sz")
                || normalized.contains("沪股")
                || normalized.contains("深股")
                || normalized.contains("上证")
                || normalized.contains("深证")) {
            return MarketHint.CN;
        }
        return MarketHint.NONE;
    }

    private String extractQuotedPayload(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }

        int firstQuote = responseBody.indexOf('"');
        int lastQuote = responseBody.lastIndexOf('"');
        if (firstQuote < 0 || lastQuote <= firstQuote) {
            return null;
        }
        return responseBody.substring(firstQuote + 1, lastQuote).trim();
    }

    private Market marketFromSuggestType(String type) {
        return switch (type) {
            case "11" -> Market.CN;
            case "31" -> Market.HK;
            case "41" -> Market.US;
            default -> null;
        };
    }

    private Market marketFromExchangePrefix(String exchangePrefix) {
        return switch (exchangePrefix.toUpperCase(Locale.ROOT)) {
            case "SH", "SZ", "BJ" -> Market.CN;
            case "HK" -> Market.HK;
            default -> Market.US;
        };
    }

    private String buildSinaSymbol(Market market, String code, String rawSymbol) {
        if (market == Market.CN) {
            if (StringUtils.hasText(rawSymbol) && rawSymbol.matches("(?i)^(sh|sz|bj)\\d{6}$")) {
                return rawSymbol.toLowerCase(Locale.ROOT);
            }
            String exchangePrefix = inferCnExchangePrefix(code);
            return exchangePrefix == null ? null : exchangePrefix + code;
        }
        if (market == Market.HK) {
            return "hk" + leftPad(code, 5);
        }
        return "gb_" + code.toLowerCase(Locale.ROOT);
    }

    private String normalizeCandidateCode(String rawCode, Market market) {
        if (!StringUtils.hasText(rawCode)) {
            return null;
        }
        String normalized = rawCode.trim();
        return market == Market.US
                ? normalized.toUpperCase(Locale.ROOT)
                : normalized;
    }

    private String inferCnExchangePrefix(String code) {
        if (!StringUtils.hasText(code) || code.length() != 6) {
            return null;
        }

        char first = code.charAt(0);
        if (first == '5' || first == '6' || first == '9') {
            return "sh";
        }
        if (first == '0' || first == '1' || first == '2' || first == '3') {
            return "sz";
        }
        if (first == '4' || first == '8') {
            return "bj";
        }
        return null;
    }

    private boolean containsChinese(String value) {
        return value != null && value.matches(".*[\\u4e00-\\u9fa5].*");
    }

    private boolean isTickerLike(String value) {
        return StringUtils.hasText(value) && value.matches("[A-Z][A-Z0-9\\.-]{0,9}");
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String composeTime(String date, String time) {
        String normalizedDate = blankToNull(date);
        String normalizedTime = blankToNull(time);
        if (normalizedDate == null) {
            return normalizedTime;
        }
        if (normalizedTime == null) {
            return normalizedDate.replace('/', '-');
        }
        return normalizedDate.replace('/', '-') + " " + normalizedTime;
    }

    private BigDecimal decimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return normalizeDecimal(new BigDecimal(value.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal subtract(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return normalizeDecimal(left.subtract(right));
    }

    private BigDecimal percentageChange(BigDecimal price, BigDecimal previousClose) {
        if (price == null || previousClose == null || previousClose.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal change = price.subtract(previousClose);
        return normalizeDecimal(change
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String leftPad(String code, int length) {
        if (!StringUtils.hasText(code) || code.length() >= length) {
            return code;
        }
        return "0".repeat(length - code.length()) + code;
    }

    private String errorJson(String input, String message) {
        JSONObject result = new JSONObject();
        result.set("status", "ERROR");
        result.set("input", input);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }

    enum Market {
        CN("A股", "CNY"),
        HK("港股", "HKD"),
        US("美股", "USD");

        private final String displayName;
        private final String currency;

        Market(String displayName, String currency) {
            this.displayName = displayName;
            this.currency = currency;
        }

        public String displayName() {
            return displayName;
        }

        public String currency() {
            return currency;
        }
    }

    enum MarketHint {
        NONE,
        CN,
        HK,
        US
    }

    record ResolvedStock(String code, String name, Market market, String sinaSymbol, boolean adr) {
    }
}
