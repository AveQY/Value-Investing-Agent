package com.aweqy.aiagent.tools;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大盘指数实时行情工具，覆盖 A 股主要指数、恒生指数与美股指数（新浪财经公开接口）。
 */
public class MarketIndexTool {

    private static final String SINA_LIST_URL_TEMPLATE = "https://hq.sinajs.cn/list=%s";
    private static final String REFERER = "https://finance.sina.com.cn";
    private static final int TIMEOUT_MS = 10_000;
    private static final Pattern VAR_LINE_PATTERN = Pattern.compile("var hq_str_([A-Za-z0-9_.]+)=\"(.*?)\";");

    private static final Map<String, String> INDEX_ALIASES = buildIndexAliases();

    private static Map<String, String> buildIndexAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("上证指数", "s_sh000001");
        map.put("上证", "s_sh000001");
        map.put("sh000001", "s_sh000001");
        map.put("000001", "s_sh000001");
        map.put("深证成指", "s_sz399001");
        map.put("深成指", "s_sz399001");
        map.put("sz399001", "s_sz399001");
        map.put("399001", "s_sz399001");
        map.put("创业板指", "s_sz399006");
        map.put("创业板", "s_sz399006");
        map.put("沪深300", "s_sh000300");
        map.put("沪深三百", "s_sh000300");
        map.put("上证50", "s_sh000016");
        map.put("中证500", "s_sh000905");
        map.put("中证1000", "s_sh000852");
        map.put("科创50", "s_sh000688");
        map.put("恒生指数", "rt_hkHSI");
        map.put("恒生", "rt_hkHSI");
        map.put("恒指", "rt_hkHSI");
        map.put("恒生科技", "rt_hkHSTECH");
        map.put("纳斯达克", "int_nasdaq");
        map.put("纳指", "int_nasdaq");
        map.put("标普500", "int_sp500");
        map.put("标普", "int_sp500");
        map.put("道琼斯", "int_dji");
        map.put("道指", "int_dji");
        return map;
    }

    @Tool(
            name = "Get market index quote",
            description = "Query realtime quotes for major market indices: A-share indices " +
                    "(上证指数、深证成指、创业板指、沪深300、中证500、上证50、科创50), " +
                    "Hong Kong indices (恒生指数、恒生科技) and US indices (纳斯达克、标普500、道琼斯). " +
                    "Input can be one index name or several names separated by comma, " +
                    "like '沪深300' or '上证指数,恒生指数,纳斯达克'."
    )
    public String getMarketIndexQuote(
            @ToolParam(description = "Index name(s) in Chinese or index code, comma separated")
            String indexQuery) {

        if (!StringUtils.hasText(indexQuery)) {
            return errorJson("请输入指数名称，例如“上证指数”、“沪深300、恒生指数”。");
        }

        try {
            List<String> requestedCodes = new ArrayList<>();
            List<String> unsupported = new ArrayList<>();
            for (String token : indexQuery.split("[,，、/\\s]+")) {
                if (!StringUtils.hasText(token)) {
                    continue;
                }
                String normalized = token.trim();
                String code = resolveIndexCode(normalized);
                if (code != null) {
                    requestedCodes.add(code);
                } else {
                    unsupported.add(normalized);
                }
            }
            if (requestedCodes.isEmpty()) {
                return errorJson("未识别到支持的指数。当前支持：" + String.join("、", INDEX_ALIASES.keySet().stream().filter(k -> !k.matches("[0-9a-z]+")).toList()));
            }

            String joined = String.join(",", requestedCodes);
            String response = HttpRequest.get(SINA_LIST_URL_TEMPLATE.formatted(joined))
                    .header("Referer", REFERER)
                    .timeout(TIMEOUT_MS)
                    .charset(CharsetUtil.CHARSET_GBK)
                    .execute()
                    .body();

            JSONArray indices = new JSONArray();
            Matcher matcher = VAR_LINE_PATTERN.matcher(response == null ? "" : response);
            while (matcher.find()) {
                JSONObject parsed = parseIndexLine(matcher.group(1), matcher.group(2));
                if (parsed != null) {
                    indices.add(parsed);
                }
            }

            JSONObject result = new JSONObject();
            result.set("status", indices.isEmpty() ? "ERROR" : "SUCCESS");
            result.set("input", indexQuery);
            result.set("indices", indices);
            if (!unsupported.isEmpty()) {
                result.set("unsupported", unsupported);
            }
            result.set("source", "新浪财经");
            result.set("note", "指数行情来自公开接口，请以交易所公布数据为准。");
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception ex) {
            return errorJson("指数行情查询失败：" + ex.getMessage());
        }
    }

    private String resolveIndexCode(String token) {
        String normalized = token.toLowerCase(Locale.ROOT).replace("指数", "");
        // 先做精确匹配，避免“恒生科技”被“恒生”这类短别名抢先命中
        for (Map.Entry<String, String> entry : INDEX_ALIASES.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(token) || entry.getKey().equalsIgnoreCase(normalized)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, String> entry : INDEX_ALIASES.entrySet()) {
            String alias = entry.getKey();
            if (alias.length() >= 3 && normalized.contains(alias)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private JSONObject parseIndexLine(String code, String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        String[] fields = payload.split(",", -1);
        JSONObject item = new JSONObject();
        item.set("sourceCode", code);
        try {
            if (code.startsWith("s_sh") || code.startsWith("s_sz")) {
                // A 股指数：名称,点位,涨跌额,涨跌幅%,成交量(手),成交额(万元)
                if (fields.length < 4) {
                    return null;
                }
                item.set("name", fields[0]);
                item.set("latest", parseDoubleSafe(fields[1]));
                item.set("change", parseDoubleSafe(fields[2]));
                item.set("changePercent", parseDoubleSafe(fields[3]));
            } else if (code.startsWith("rt_hk")) {
                // 港股指数：代码,名称,开盘,昨收,最高,最低,最新,涨跌额,涨跌幅,...,52周高,52周低,日期,时间
                if (fields.length < 9) {
                    return null;
                }
                item.set("name", fields[1]);
                item.set("latest", parseDoubleSafe(fields[6]));
                item.set("change", parseDoubleSafe(fields[7]));
                item.set("changePercent", parseDoubleSafe(fields[8]));
                if (fields.length > 17) {
                    item.set("date", fields[17]);
                }
                if (fields.length > 15) {
                    item.set("week52High", parseDoubleSafe(fields[15]));
                    item.set("week52Low", parseDoubleSafe(fields[16]));
                }
            } else if (code.startsWith("int_")) {
                // 美股指数：名称,点位,涨跌额,涨跌幅%
                if (fields.length < 4) {
                    return null;
                }
                item.set("name", fields[0]);
                item.set("latest", parseDoubleSafe(fields[1]));
                item.set("change", parseDoubleSafe(fields[2]));
                item.set("changePercent", parseDoubleSafe(fields[3]));
            } else {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return item;
    }

    private Double parseDoubleSafe(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private String errorJson(String message) {
        JSONObject error = new JSONObject();
        error.set("status", "ERROR");
        error.set("message", message);
        return JSONUtil.toJsonPrettyStr(error);
    }
}
