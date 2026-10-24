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
 * 人民币汇率工具（新浪财经公开接口）：查询美元、欧元、港元、日元、英镑等兑人民币汇率。
 */
public class ExchangeRateTool {

    private static final String SINA_LIST_URL_TEMPLATE = "https://hq.sinajs.cn/list=%s";
    private static final String REFERER = "https://finance.sina.com.cn";
    private static final int TIMEOUT_MS = 10_000;
    private static final Pattern VAR_LINE_PATTERN = Pattern.compile("var hq_str_fx_([a-z0-9]+)=\"(.*?)\";");

    // 常用货币别名 → 新浪完整代码（兑人民币，注意必须带 fx_ 前缀）
    private static final Map<String, String> CURRENCY_ALIASES = buildCurrencyAliases();

    private static Map<String, String> buildCurrencyAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("美元", "fx_susdcny");
        map.put("usd", "fx_susdcny");
        map.put("欧元", "fx_seurcny");
        map.put("eur", "fx_seurcny");
        map.put("港元", "fx_shkdcny");
        map.put("港币", "fx_shkdcny");
        map.put("hkd", "fx_shkdcny");
        map.put("日元", "fx_sjpycny");
        map.put("jpy", "fx_sjpycny");
        map.put("英镑", "fx_sgbpcny");
        map.put("gbp", "fx_sgbpcny");
        map.put("澳元", "fx_saudcny");
        map.put("aud", "fx_saudcny");
        map.put("加元", "fx_scadcny");
        map.put("cad", "fx_scadcny");
        map.put("人民币", "fx_susdcny");
        map.put("cny", "fx_susdcny");
        return map;
    }

    @Tool(
            name = "Get exchange rate",
            description = "Query realtime exchange rates against CNY (人民币) for major currencies: " +
                    "美元 USD、欧元 EUR、港元 HKD、日元 JPY、英镑 GBP、澳元 AUD、加元 CAD. " +
                    "Input can be one currency or several separated by comma, like '美元' or '美元,日元,港元'."
    )
    public String getExchangeRate(
            @ToolParam(description = "Currency name(s) in Chinese or ISO code, comma separated; empty means USD/CNY")
            String currencyQuery) {

        try {
            List<String> requestedCodes = new ArrayList<>();
            List<String> unsupported = new ArrayList<>();
            String query = currencyQuery == null ? "" : currencyQuery.trim();
            if (!StringUtils.hasText(query)) {
                requestedCodes.add("susdcny");
            } else {
                for (String token : query.split("[,，、/\\s]+")) {
                    if (!StringUtils.hasText(token)) {
                        continue;
                    }
                    String code = resolveCurrencyCode(token.trim());
                    if (code != null) {
                        if (!requestedCodes.contains(code)) {
                            requestedCodes.add(code);
                        }
                    } else {
                        unsupported.add(token.trim());
                    }
                }
            }
            if (requestedCodes.isEmpty()) {
                return errorJson("未识别到支持的货币。当前支持：" + String.join("、", "美元", "欧元", "港元", "日元", "英镑", "澳元", "加元"));
            }

            String response = HttpRequest.get(SINA_LIST_URL_TEMPLATE.formatted(String.join(",", requestedCodes)))
                    .header("Referer", REFERER)
                    .timeout(TIMEOUT_MS)
                    .charset(CharsetUtil.CHARSET_GBK)
                    .execute()
                    .body();

            JSONArray rates = new JSONArray();
            Matcher matcher = VAR_LINE_PATTERN.matcher(response == null ? "" : response);
            while (matcher.find()) {
                JSONObject item = parseRateLine(matcher.group(1), matcher.group(2));
                if (item != null) {
                    rates.add(item);
                }
            }

            JSONObject result = new JSONObject();
            result.set("status", rates.isEmpty() ? "ERROR" : "SUCCESS");
            result.set("input", currencyQuery);
            result.set("rates", rates);
            if (!unsupported.isEmpty()) {
                result.set("unsupported", unsupported);
            }
            result.set("source", "新浪财经");
            result.set("note", "汇率为公开接口数据，请以银行实际牌价为准。");
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception ex) {
            return errorJson("汇率查询失败：" + ex.getMessage());
        }
    }

    private String resolveCurrencyCode(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : CURRENCY_ALIASES.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized) || normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private JSONObject parseRateLine(String code, String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        String[] fields = payload.split(",", -1);
        if (fields.length < 18) {
            return null;
        }
        JSONObject item = new JSONObject();
        item.set("pairCode", code.replaceFirst("^s", "").toUpperCase(Locale.ROOT));
        item.set("name", fields[9]);
        item.set("rate", parseDoubleSafe(fields[8]));
        item.set("date", fields[17]);
        item.set("time", fields[0]);
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
