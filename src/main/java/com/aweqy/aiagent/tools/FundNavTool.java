package com.aweqy.aiagent.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 场外基金净值工具（天天基金公开接口）：
 * 支持基金代码或名称检索，返回最新单位净值/累计净值、近期涨跌与最近若干个交易日净值明细。
 */
public class FundNavTool {

    private static final String FUND_SEARCH_URL_TEMPLATE =
            "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key=%s";
    private static final String FUND_NAV_URL_TEMPLATE =
            "https://api.fund.eastmoney.com/f10/lsjz?fundCode=%s&pageIndex=1&pageSize=%d";
    private static final String FUND_REFERER = "https://fundf10.eastmoney.com/";
    private static final int TIMEOUT_MS = 10_000;
    private static final int NAV_ROWS = 20;
    private static final Pattern FUND_CODE_PATTERN = Pattern.compile("\\b(\\d{6})\\b");

    @Tool(
            name = "Get fund NAV",
            description = "Query the latest NAV (net asset value) and recent daily NAV changes for a Chinese " +
                    "mutual fund (场外基金). Input can be a 6-digit fund code or fund name, " +
                    "like '110022', '易方达消费行业', or '沪深300指数基金'. " +
                    "Returns latest unit NAV, cumulative NAV, recent change percent and recent daily NAV list."
    )
    public String getFundNav(
            @ToolParam(description = "6-digit fund code, fund name, or a short question containing the fund")
            String fundQuery) {

        if (!StringUtils.hasText(fundQuery)) {
            return errorJson(fundQuery, "请输入基金代码或基金名称，例如 110022、易方达消费行业。");
        }

        try {
            String fundCode = resolveFundCode(fundQuery.trim());
            if (!StringUtils.hasText(fundCode)) {
                return errorJson(fundQuery, "未找到匹配的基金，请提供更准确的 6 位基金代码或基金名称。");
            }

            String url = FUND_NAV_URL_TEMPLATE.formatted(fundCode, NAV_ROWS);
            String response = HttpRequest.get(url)
                    .header("Referer", FUND_REFERER)
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .body();
            if (!StringUtils.hasText(response)) {
                return errorJson(fundQuery, "净值接口没有返回数据。");
            }

            JSONObject root = JSONUtil.parseObj(response);
            JSONArray list = root.getJSONObject("Data") == null ? null : root.getJSONObject("Data").getJSONArray("LSJZList");
            if (list == null || list.isEmpty()) {
                return errorJson(fundQuery, "基金 " + fundCode + " 没有查到净值记录，请确认代码是否正确。");
            }

            // 接口按日期倒序返回：第一条最新
            JSONObject latest = list.getJSONObject(0);
            JSONObject result = new JSONObject();
            result.set("status", "SUCCESS");
            result.set("input", fundQuery);
            result.set("fundCode", fundCode);
            result.set("fundName", resolveFundName(fundQuery, fundCode));
            result.set("navDate", latest.getStr("FSRQ"));
            result.set("unitNav", latest.getStr("DWJZ"));
            result.set("cumulativeNav", latest.getStr("LJJZ"));
            result.set("latestDailyChangePercent", parsePercent(latest.getStr("JZZZL")));
            result.set("purchaseStatus", latest.getStr("SGZT"));
            result.set("redeemStatus", latest.getStr("SHZT"));

            JSONArray rows = new JSONArray();
            for (Object item : list) {
                JSONObject row = (JSONObject) item;
                JSONObject line = new JSONObject();
                line.set("date", row.getStr("FSRQ"));
                line.set("unitNav", row.getStr("DWJZ"));
                line.set("cumulativeNav", row.getStr("LJJZ"));
                line.set("dailyChangePercent", parsePercent(row.getStr("JZZZL")));
                rows.add(line);
            }
            result.set("recentNavs", rows);
            if (list.size() >= 6) {
                double weekAgo = parseDoubleSafe(list.getJSONObject(Math.min(4, list.size() - 1)).getStr("DWJZ"));
                double latestNav = parseDoubleSafe(latest.getStr("DWJZ"));
                if (weekAgo > 0 && latestNav > 0) {
                    result.set("last5TradingDaysChangePercent",
                            BigDecimal.valueOf((latestNav - weekAgo) / weekAgo * 100)
                                    .setScale(2, RoundingMode.HALF_UP).doubleValue());
                }
            }
            result.set("source", "天天基金");
            result.set("note", "净值为公开接口数据，QDII/场内基金可能更新滞后，请以基金公司公告为准。");
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception ex) {
            return errorJson(fundQuery, "基金净值查询失败：" + ex.getMessage());
        }
    }

    private String resolveFundCode(String query) {
        Matcher codeMatcher = FUND_CODE_PATTERN.matcher(query);
        if (codeMatcher.find()) {
            return codeMatcher.group(1);
        }

        try {
            String keyword = extractKeyword(query);
            if (!StringUtils.hasText(keyword)) {
                return null;
            }
            String url = FUND_SEARCH_URL_TEMPLATE.formatted(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
            String response = HttpRequest.get(url)
                    .header("Referer", "https://fund.eastmoney.com/")
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .body();
            if (!StringUtils.hasText(response)) {
                return null;
            }
            JSONObject root = JSONUtil.parseObj(response);
            JSONArray datas = root.getJSONArray("Datas");
            if (datas == null || datas.isEmpty()) {
                return null;
            }
            JSONObject first = datas.getJSONObject(0);
            String code = first == null ? null : first.getStr("CODE");
            return StringUtils.hasText(code) ? code : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveFundName(String query, String fundCode) {
        // 搜索路径顺便取名称；直接代码路径则保持空，避免多一次请求
        try {
            String keyword = extractKeyword(query);
            if (!StringUtils.hasText(keyword) || keyword.equals(fundCode)) {
                return null;
            }
            String url = FUND_SEARCH_URL_TEMPLATE.formatted(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
            String response = HttpRequest.get(url)
                    .header("Referer", "https://fund.eastmoney.com/")
                    .timeout(TIMEOUT_MS)
                    .execute()
                    .body();
            JSONObject root = JSONUtil.parseObj(response);
            JSONArray datas = root.getJSONArray("Datas");
            if (datas != null && !datas.isEmpty()) {
                JSONObject first = datas.getJSONObject(0);
                String name = first == null ? null : first.getStr("NAME");
                if (StringUtils.hasText(name)) {
                    return name.replaceAll("<[^>]+>", "").split("_")[0];
                }
            }
        } catch (Exception ignored) {
            // 名称获取失败不影响主结果
        }
        return null;
    }

    private String extractKeyword(String query) {
        return query
                .replaceAll("[，。！？、：；（）【】《》“”‘’,.!?;:()\\[\\]{}<>\"']", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|查一下|查询|看一下|想看|最新|当前|今日|今天|净值|涨跌|涨了|跌了|多少|怎么样|基金|的|了|呢|吗)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Double parsePercent(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private double parseDoubleSafe(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ex) {
            return -1;
        }
    }

    private String errorJson(String input, String message) {
        JSONObject error = new JSONObject();
        error.set("status", "ERROR");
        error.set("input", input);
        error.set("message", message);
        return JSONUtil.toJsonPrettyStr(error);
    }
}
