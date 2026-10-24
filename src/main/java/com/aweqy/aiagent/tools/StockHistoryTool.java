package com.aweqy.aiagent.tools;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 股个股与主要指数的历史行情工具（腾讯行情公开接口，前复权日线），
 * 返回区间统计摘要 + 最近若干个交易日收盘明细，用于分析走势、涨跌幅与回撤。
 */
public class StockHistoryTool {

    private static final String SINA_SUGGEST_URL_TEMPLATE =
            "https://suggest3.sinajs.cn/suggest/type=11&key=%s&name=suggestdata";
    private static final String TENCENT_KLINE_URL_TEMPLATE =
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s,day,,,%d,qfq";
    private static final int TIMEOUT_MS = 15_000;
    private static final int MIN_DAYS = 10;
    private static final int MAX_DAYS = 1000;
    private static final int DETAIL_ROWS = 30;

    private static final Map<String, String> INDEX_CODES = buildIndexCodes();

    private static Map<String, String> buildIndexCodes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("上证指数", "sh000001");
        map.put("上证", "sh000001");
        map.put("深证成指", "sz399001");
        map.put("深成指", "sz399001");
        map.put("创业板指", "sz399006");
        map.put("创业板", "sz399006");
        map.put("沪深300", "sh000300");
        map.put("上证50", "sh000016");
        map.put("中证500", "sh000905");
        map.put("科创50", "sh000688");
        return map;
    }

    @Tool(
            name = "Get stock price history",
            description = "Query historical daily prices (front-adjusted) for one A-share stock or major index, " +
                    "and get computed statistics over the window: interval change percent, highest/lowest close " +
                    "with dates, drawdown from interval high, and recent daily closes. " +
                    "Use it for questions like '茅台近一年走势', '600519 近三年涨了多少', '沪深300 今年表现如何'."
    )
    public String getStockPriceHistory(
            @ToolParam(description = "Stock code, stock name, index name, or a short question containing the target")
            String stockQuery,
            @ToolParam(description = "Number of trading days to look back, 10-1000, e.g. 250 for about one year", required = false)
            Integer days) {

        if (!StringUtils.hasText(stockQuery)) {
            return errorJson(stockQuery, "请输入股票代码、股票名称或指数名称。");
        }

        int window = clampDays(days);
        try {
            String symbol = resolveSymbol(stockQuery.trim());
            if (!StringUtils.hasText(symbol)) {
                return errorJson(stockQuery, "未找到匹配的 A 股股票或指数，请提供更准确的代码或名称，例如 600519、贵州茅台、沪深300。");
            }

            // 请求量略大于窗口，覆盖停牌等造成的交易日缺口，再裁剪到指定天数。
            String url = TENCENT_KLINE_URL_TEMPLATE.formatted(symbol, Math.min(MAX_DAYS + 200, window * 2 + 60));
            String response = HttpRequest.get(url)
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .body();
            List<KlineRow> rows = parseKlineRows(response, symbol);
            if (rows.isEmpty()) {
                return errorJson(stockQuery, "历史行情接口没有返回数据，请确认代码是否正确。");
            }
            if (rows.size() > window) {
                rows = rows.subList(rows.size() - window, rows.size());
            }

            JSONObject result = buildSummary(stockQuery, symbol, rows);
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception ex) {
            return errorJson(stockQuery, "历史行情查询失败：" + ex.getMessage());
        }
    }

    private JSONObject buildSummary(String stockQuery, String symbol, List<KlineRow> rows) {
        KlineRow first = rows.get(0);
        KlineRow last = rows.get(rows.size() - 1);
        KlineRow highest = rows.stream().max((a, b) -> Double.compare(a.close, b.close)).orElse(last);
        KlineRow lowest = rows.stream().min((a, b) -> Double.compare(a.close, b.close)).orElse(last);
        double intervalChangePercent = first.close > 0 ? (last.close - first.close) / first.close * 100 : 0;
        double drawdownFromHigh = highest.close > 0 ? (last.close - highest.close) / highest.close * 100 : 0;

        JSONArray detail = new JSONArray();
        int fromIndex = Math.max(0, rows.size() - DETAIL_ROWS);
        for (int i = fromIndex; i < rows.size(); i++) {
            KlineRow row = rows.get(i);
            JSONObject item = new JSONObject();
            item.set("date", row.date);
            item.set("close", round2(row.close));
            if (i > 0) {
                double prev = rows.get(i - 1).close;
                item.set("changePercent", prev > 0 ? round2((row.close - prev) / prev * 100) : null);
            }
            detail.add(item);
        }

        JSONObject result = new JSONObject();
        result.set("status", "SUCCESS");
        result.set("input", stockQuery);
        result.set("symbol", symbol);
        result.set("tradingDays", rows.size());
        result.set("windowStart", first.date);
        result.set("windowEnd", last.date);
        result.set("latestClose", round2(last.close));
        result.set("intervalChangePercent", round2(intervalChangePercent));
        result.set("highestClose", round2(highest.close));
        result.set("highestDate", highest.date);
        result.set("lowestClose", round2(lowest.close));
        result.set("lowestDate", lowest.date);
        result.set("drawdownFromHighPercent", round2(drawdownFromHigh));
        result.set("note", "统计基于前复权收盘价；最近 " + detail.size() + " 个交易日明细如下，完整区间共 " + rows.size() + " 个交易日。");
        result.set("recentCloses", detail);
        result.set("source", "腾讯行情");
        return result;
    }

    private List<KlineRow> parseKlineRows(String response, String symbol) {
        List<KlineRow> rows = new ArrayList<>();
        if (!StringUtils.hasText(response)) {
            return rows;
        }
        JSONObject root = JSONUtil.parseObj(response);
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return rows;
        }
        JSONObject symbolNode = data.getJSONObject(symbol);
        if (symbolNode == null) {
            return rows;
        }
        // 个股优先返回 qfqday，指数可能只有 day
        JSONArray kline = symbolNode.getJSONArray("qfqday");
        if (kline == null) {
            kline = symbolNode.getJSONArray("day");
        }
        if (kline == null) {
            return rows;
        }
        for (Object item : kline) {
            if (!(item instanceof JSONArray row) || row.size() < 3) {
                continue;
            }
            String date = String.valueOf(row.get(0));
            double close = parseDoubleSafe(String.valueOf(row.get(2)));
            if (StringUtils.hasText(date) && !Double.isNaN(close)) {
                rows.add(new KlineRow(date, close));
            }
        }
        return rows;
    }

    /**
     * 把用户输入解析成腾讯行情代码：指数别名 → sh000001 形式；
     * 6 位数字自动推断交易所前缀；其余走新浪 suggest 搜索 A 股。
     */
    private String resolveSymbol(String query) {
        String normalized = query.trim();
        for (Map.Entry<String, String> entry : INDEX_CODES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        Matcher symbolMatcher = Pattern.compile("(?i)\\b(?:sh|sz)(\\d{6})\\b").matcher(normalized);
        if (symbolMatcher.find()) {
            return symbolMatcher.group().toLowerCase(Locale.ROOT);
        }

        Matcher codeMatcher = Pattern.compile("\\b(\\d{6})\\b").matcher(normalized);
        if (codeMatcher.find()) {
            String prefix = inferExchangePrefix(codeMatcher.group(1));
            if (prefix != null) {
                return prefix + codeMatcher.group(1);
            }
        }

        return searchBySuggest(extractKeyword(normalized));
    }

    private String searchBySuggest(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        try {
            String url = SINA_SUGGEST_URL_TEMPLATE.formatted(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
            String response = HttpRequest.get(url)
                    .header("Referer", "https://finance.sina.com.cn")
                    .timeout(TIMEOUT_MS)
                    .charset(CharsetUtil.CHARSET_GBK)
                    .execute()
                    .body();
            int firstQuote = response == null ? -1 : response.indexOf('"');
            int lastQuote = response == null ? -1 : response.lastIndexOf('"');
            if (firstQuote < 0 || lastQuote <= firstQuote) {
                return null;
            }
            String payload = response.substring(firstQuote + 1, lastQuote);
            for (String row : payload.split(";")) {
                String[] fields = row.split(",", -1);
                if (fields.length < 5) {
                    continue;
                }
                // fields[3] 是新浪完整代码（如 sh600519 / sz000001），正好兼容腾讯行情代码格式
                String symbol = fields[3] == null ? "" : fields[3].toLowerCase(Locale.ROOT);
                if (symbol.matches("(sh|sz)\\d{6}")) {
                    return symbol;
                }
            }
        } catch (Exception ignored) {
            // 搜索失败由调用方兜底报错
        }
        return null;
    }

    private String extractKeyword(String query) {
        return query
                .replaceAll("[，。！？、：；（）【】《》“”‘’,.!?;:()\\[\\]{}<>\"']", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|查一下|查询|看一下|想看|分析|最近|近|过去|今年|去年|今年以来|近一年|近三年|走势|行情|表现|涨跌|涨了|跌了|多少|怎么样|如何|历史|K线|k线|回撤|股价|价格|的|了|呢|吗)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String inferExchangePrefix(String code) {
        char first = code.charAt(0);
        return switch (first) {
            case '6' -> "sh";
            case '0', '3' -> "sz";
            default -> null;
        };
    }

    private int clampDays(Integer days) {
        if (days == null) {
            return 250;
        }
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
    }

    private double parseDoubleSafe(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String errorJson(String input, String message) {
        JSONObject error = new JSONObject();
        error.set("status", "ERROR");
        error.set("input", input);
        error.set("message", message);
        return JSONUtil.toJsonPrettyStr(error);
    }

    private record KlineRow(String date, double close) {
    }
}
