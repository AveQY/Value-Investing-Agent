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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 股个股财报搜索工具，基于东方财富 F10 公开接口整理近年的核心财报指标。
 */
public class AShareFinancialReportTool {

    private static final String EASTMONEY_SEARCH_URL_TEMPLATE =
            "https://searchapi.eastmoney.com/api/suggest/get?input=%s&type=14&token=D43BF722C8E33BDC906FB84D85E326E8";
    private static final String EASTMONEY_F10_INDEX_URL_TEMPLATE =
            "https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/Index?type=web&code=%s";
    private static final String EASTMONEY_ZYZB_URL_TEMPLATE =
            "https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/ZYZBAjaxNew?type=0&code=%s";
    private static final String EASTMONEY_BFBBB_URL_TEMPLATE =
            "https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/bfbbbAjaxNew?code=%s&ctype=%s&type=0";
    private static final String EASTMONEY_INCOME_URL_TEMPLATE =
            "https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/lrbAjaxNew?companyType=%s&reportDateType=0&reportType=1&dates=%s&code=%s";
    private static final String EASTMONEY_BALANCE_URL_TEMPLATE =
            "https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/zcfzbAjaxNew?companyType=%s&reportDateType=0&reportType=1&dates=%s&code=%s";
    private static final String EASTMONEY_CASHFLOW_URL_TEMPLATE =
            "https://emweb.securities.eastmoney.com/PC_HSF10/NewFinanceAnalysis/xjllbAjaxNew?companyType=%s&reportDateType=0&reportType=1&dates=%s&code=%s";

    private static final Pattern EXACT_STOCK_IDENTIFIER_PATTERN = Pattern.compile(
            "(?i)^(?:sh|sz|bj)\\d{6}$|^\\d{6}(?:\\.(?:SH|SZ|BJ))?$"
    );
    private static final Pattern EMBEDDED_STOCK_IDENTIFIER_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:sh|sz|bj)\\d{6}\\b|\\b\\d{6}(?:\\.(?:SH|SZ|BJ))?\\b)"
    );
    private static final Pattern COMPANY_TYPE_PATTERN = Pattern.compile("id=\"hidctype\"\\s+type=\"hidden\"\\s+value=\"(\\d+)\"");
    private static final Pattern MULTI_TARGET_SEPARATOR_PATTERN = Pattern.compile("(?i)\\s*(?:,|，|、|/|\\\\|vs|和|与|跟)\\s*");

    private static final Set<String> MULTI_TARGET_HINTS = Set.of(
            "对比", "比较", "vs", "versus", "pk", "哪个好", "谁更", "哪个更", "二选一"
    );
    private static final Set<String> STOP_WORDS = Set.of(
            "最近", "近年", "财报", "年报", "季报", "半年报", "一季报", "三季报",
            "营收", "营业收入", "收入", "利润", "净利润", "扣非", "扣非净利润",
            "毛利率", "现金", "现金流", "现金及其等价物", "货币资金",
            "带息债务", "资产负债率", "总资产", "周转率", "负债", "roe", "roic",
            "分析", "搜索", "查询", "查", "看看", "请问", "请帮我", "帮我", "麻烦", "值不值得"
    );
    private static final Set<String> ENGLISH_TICKER_STOP_WORDS = Set.of(
            "AI", "API", "HTTP", "HTTPS", "HTML", "JSON", "JAVA", "SQL", "ETF", "PDF", "DOC", "TXT"
    );

    @Tool(
            name = "Search A-share financial reports",
            description = "Search public financial reports for one A-share stock at a time. " +
                    "Supports stock code, company name, or a short question containing the target stock. " +
                    "Returns recent core financial metrics or selected statement sections from Eastmoney F10. " +
                    "Best for annual reports, ROE, revenue, net profit, cash flow, cash and equivalents, debt, and balance sheet analysis."
    )
    public String searchAshareFinancialReports(
            @ToolParam(description = "A-share stock code, company name, or a short question containing the target stock")
            String stockQuery,
            @ToolParam(description = "How many reports to return. Default 5, max 8")
            Integer limit,
            @ToolParam(description = "Report period: annual, latest, or all. Default annual")
            String period,
            @ToolParam(description = "Result focus: core, income, balance, or cashflow. Default core")
            String focus) {

        String extractedQuery = extractTargetCandidate(stockQuery);
        if (!StringUtils.hasText(extractedQuery)) {
            return errorJson(stockQuery, "请输入单只 A 股股票代码或公司名称，例如 600519、000001、贵州茅台。");
        }

        List<String> detectedTargets = detectMultipleTargets(stockQuery);
        if (detectedTargets.size() > 1) {
            return errorJson(stockQuery, "该工具一次只支持一只 A 股标的，请只保留一个股票名称或代码。");
        }

        int effectiveLimit = normalizeLimit(limit);
        ReportPeriod reportPeriod = ReportPeriod.from(period);
        ReportFocus reportFocus = ReportFocus.from(focus);

        try {
            ResolvedStock resolvedStock = resolveStock(extractedQuery);
            if (resolvedStock == null) {
                return errorJson(stockQuery, "未识别到匹配的 A 股标的，请提供更准确的股票代码或公司名称。");
            }

            String companyType = fetchCompanyType(resolvedStock.eastCode());
            List<JSONObject> allIndicatorReports = fetchIndicatorReports(resolvedStock.eastCode());
            List<JSONObject> indicatorReports = selectReports(allIndicatorReports, reportPeriod, effectiveLimit);
            if (indicatorReports.isEmpty()) {
                return errorJson(stockQuery, "暂未查到可用的财报记录，请稍后重试或更换股票代码。");
            }

            List<String> reportDates = buildStatementReportDates(allIndicatorReports, indicatorReports, reportPeriod);

            Map<String, JSONObject> percentageRows = fetchPercentageRows(resolvedStock.eastCode(), companyType, reportPeriod);
            Map<String, JSONObject> incomeRows = fetchStatementRows(
                    EASTMONEY_INCOME_URL_TEMPLATE.formatted(companyType, String.join(",", reportDates), resolvedStock.eastCode())
            );
            Map<String, JSONObject> balanceRows = fetchStatementRows(
                    EASTMONEY_BALANCE_URL_TEMPLATE.formatted(companyType, String.join(",", reportDates), resolvedStock.eastCode())
            );
            Map<String, JSONObject> cashflowRows = fetchStatementRows(
                    EASTMONEY_CASHFLOW_URL_TEMPLATE.formatted(companyType, String.join(",", reportDates), resolvedStock.eastCode())
            );

            JSONObject result = new JSONObject();
            result.set("status", "SUCCESS");
            result.set("input", stockQuery);
            result.set("lookupInput", extractedQuery);
            result.set("stock", resolvedStock.toJson());
            result.set("companyType", companyType);
            result.set("period", reportPeriod.code());
            result.set("focus", reportFocus.code());
            result.set("limit", effectiveLimit);
            result.set("currency", "CNY");
            result.set("source", "东方财富 F10");
            result.set("note", "金额单位为元；百分比字段按百分数口径展示；带息债务与经营性负债为估算值，金融类公司需结合行业口径进一步确认；存货周转率优先取东方财富指标，缺失时按营业成本/平均存货估算。");
            result.set("reports", buildReports(
                    indicatorReports,
                    percentageRows,
                    incomeRows,
                    balanceRows,
                    cashflowRows,
                    reportFocus
            ));
            return JSONUtil.toJsonPrettyStr(result);
        } catch (Exception ex) {
            return errorJson(stockQuery, "A 股财报搜索失败：" + ex.getMessage());
        }
    }

    String extractTargetCandidate(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }

        List<String> explicitIdentifiers = extractExplicitStockIdentifiers(message);
        if (explicitIdentifiers.size() == 1) {
            return explicitIdentifiers.get(0);
        }

        String cleaned = String.valueOf(message)
                .replaceAll("[，。！？、：；（）【】《》“”‘’,.!?;:()/\\\\\\[\\]{}<>\"']", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|麻烦|查一下|查下|查询|搜一下|搜下|看一下|看下|想看|想查|帮忙|分析一下|分析|拆解一下|拆解|判断一下|判断|点评一下|解读一下|告诉我|对比一下|对比|比较一下|比较|值不值得买|值得买吗|能买吗|适合长期持有吗|长期|短期|现在|最新|当前|今日|今天|最近\\d+年|近\\d+年|最近五年|近五年|股票|个股|公司|企业|上市公司|财报|年报|季报|半年报|一季报|三季报|营收|营业收入|收入|利润|净利润|扣非|扣非净利润|毛利率|现金流|现金及其等价物|货币资金|资产负债率|带息债务|总资产|周转率|ROE|ROIC|步骤|明细|数据|怎么看|搜索|查询)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleanCandidateTokens(cleaned);
        if (!StringUtils.hasText(cleaned)) {
            return "";
        }

        if (containsAnyHint(message, MULTI_TARGET_HINTS)) {
            List<String> targets = detectMultipleTargets(message);
            if (targets.size() > 1) {
                return "";
            }
        }

        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40).trim();
    }

    private String cleanCandidateTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        List<String> tokens = new ArrayList<>();
        for (String token : value.split("\\s+")) {
            String normalized = token == null ? "" : token.trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (normalized.equals("/")
                    || normalized.equals("\\")
                    || normalized.equals("请")
                    || normalized.equals("和")
                    || normalized.equals("与")
                    || normalized.equals("及")) {
                continue;
            }
            tokens.add(normalized);
        }

        String cleaned = String.join(" ", tokens).trim();
        if (cleaned.matches(".*[\\u4e00-\\u9fa5].*")) {
            return cleaned.replace(" ", "");
        }
        return cleaned;
    }

    List<String> extractExplicitStockIdentifiers(String message) {
        if (!StringUtils.hasText(message)) {
            return List.of();
        }

        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = EMBEDDED_STOCK_IDENTIFIER_PATTERN.matcher(message);
        while (matcher.find()) {
            String token = matcher.group();
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String normalized = token.trim();
            if (ENGLISH_TICKER_STOP_WORDS.contains(normalized.toUpperCase(Locale.ROOT))) {
                continue;
            }
            identifiers.add(normalized);
        }
        return identifiers.stream().toList();
    }

    List<String> detectMultipleTargets(String message) {
        List<String> explicitIdentifiers = extractExplicitStockIdentifiers(message);
        if (explicitIdentifiers.size() > 1) {
            return explicitIdentifiers;
        }

        if (!containsAnyHint(message, MULTI_TARGET_HINTS)
                && !String.valueOf(message).contains("、")
                && !String.valueOf(message).contains("，")
                && !String.valueOf(message).contains(",")
                && !String.valueOf(message).contains("和")
                && !String.valueOf(message).contains("与")) {
            return List.of();
        }

        String cleaned = String.valueOf(message)
                .replaceAll("[！？。；：()（）【】\\[\\]{}<>\"“”‘’]", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|麻烦|查一下|查下|查询|搜一下|搜下|看一下|看下|想看|想查|帮忙|分析一下|分析|点评一下|解读一下|对比一下|对比|比较一下|比较|告诉我|觉得|如何|怎么样|值不值得|值不值得买|值得买吗|能不能买|能买吗)", " ")
                .replaceAll("\\s+", " ")
                .trim();

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String part : MULTI_TARGET_SEPARATOR_PATTERN.split(cleaned)) {
            String candidate = extractTargetCandidate(part);
            if (looksLikeStockTarget(candidate)) {
                targets.add(candidate.trim());
            }
        }
        return targets.stream().toList();
    }

    List<ResolvedStock> parseSearchResponse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return List.of();
        }

        JSONObject root = JSONUtil.parseObj(responseBody);
        JSONObject quotationTable = root.getJSONObject("QuotationCodeTable");
        JSONArray candidates = quotationTable == null ? new JSONArray() : quotationTable.getJSONArray("Data");
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<ResolvedStock> result = new ArrayList<>();
        for (Object candidateObject : candidates) {
            if (!(candidateObject instanceof JSONObject candidate)) {
                continue;
            }

            String classify = candidate.getStr("Classify");
            String securityTypeName = candidate.getStr("SecurityTypeName");
            if (!"AStock".equalsIgnoreCase(classify)
                    && !containsAnyAshareHint(securityTypeName, candidate.getStr("SecurityType"))) {
                continue;
            }

            String code = candidate.getStr("Code");
            if (!StringUtils.hasText(code) || !code.matches("\\d{6}")) {
                continue;
            }

            String marketPrefix = inferExchangePrefix(code, candidate.getStr("QuoteID"));
            if (!StringUtils.hasText(marketPrefix)) {
                continue;
            }

            result.add(new ResolvedStock(
                    code,
                    candidate.getStr("Name"),
                    marketPrefix,
                    marketPrefix + code,
                    buildSecuCode(code, marketPrefix)
            ));
        }
        return result;
    }

    ResolvedStock resolveDirectSymbol(String lookupInput) {
        if (!StringUtils.hasText(lookupInput)) {
            return null;
        }

        String normalized = lookupInput.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("^(SH|SZ|BJ)(\\d{6})$").matcher(normalized);
        if (matcher.matches()) {
            return new ResolvedStock(
                    matcher.group(2),
                    null,
                    matcher.group(1),
                    matcher.group(1) + matcher.group(2),
                    matcher.group(2) + "." + matcher.group(1)
            );
        }

        matcher = Pattern.compile("^(\\d{6})\\.(SH|SZ|BJ)$").matcher(normalized);
        if (matcher.matches()) {
            return new ResolvedStock(
                    matcher.group(1),
                    null,
                    matcher.group(2),
                    matcher.group(2) + matcher.group(1),
                    matcher.group(1) + "." + matcher.group(2)
            );
        }

        if (normalized.matches("^\\d{6}$")) {
            String marketPrefix = inferExchangePrefix(normalized, null);
            if (StringUtils.hasText(marketPrefix)) {
                return new ResolvedStock(
                        normalized,
                        null,
                        marketPrefix,
                        marketPrefix + normalized,
                        normalized + "." + marketPrefix
                );
            }
        }

        return null;
    }

    private ResolvedStock resolveStock(String lookupInput) {
        ResolvedStock direct = resolveDirectSymbol(lookupInput);
        if (direct != null) {
            return direct;
        }

        List<ResolvedStock> candidates = parseSearchResponse(httpGet(EASTMONEY_SEARCH_URL_TEMPLATE.formatted(
                URLEncoder.encode(lookupInput, StandardCharsets.UTF_8)
        )));
        if (candidates.isEmpty()) {
            return null;
        }

        String normalizedQuery = normalizeText(lookupInput);
        return candidates.stream()
                .max(Comparator.comparingInt(candidate -> scoreCandidate(candidate, normalizedQuery)))
                .orElse(null);
    }

    List<JSONObject> selectReports(List<JSONObject> reports, ReportPeriod period, int limit) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }

        List<JSONObject> filtered = reports.stream()
                .filter(report -> matchesReportPeriod(report, period))
                .sorted(Comparator.comparing((JSONObject report) -> normalizeDateTime(report.getStr("REPORT_DATE")))
                        .reversed())
                .toList();

        if (period == ReportPeriod.LATEST) {
            return filtered.isEmpty() ? List.of() : List.of(filtered.get(0));
        }
        return filtered.stream().limit(limit).toList();
    }

    private List<String> buildStatementReportDates(List<JSONObject> allIndicatorReports,
                                                   List<JSONObject> selectedReports,
                                                   ReportPeriod period) {
        LinkedHashSet<String> reportDates = new LinkedHashSet<>();
        if (selectedReports != null) {
            for (JSONObject report : selectedReports) {
                String reportDate = normalizeDate(report.getStr("REPORT_DATE"));
                if (StringUtils.hasText(reportDate)) {
                    reportDates.add(reportDate);
                }
            }
        }

        if (selectedReports == null || selectedReports.isEmpty() || allIndicatorReports == null || allIndicatorReports.isEmpty()) {
            return reportDates.stream().toList();
        }

        String oldestSelectedDate = normalizeDate(selectedReports.get(selectedReports.size() - 1).getStr("REPORT_DATE"));
        List<JSONObject> sortedCandidates = allIndicatorReports.stream()
                .filter(report -> matchesReportPeriod(report, period))
                .sorted(Comparator.comparing((JSONObject report) -> normalizeDateTime(report.getStr("REPORT_DATE"))).reversed())
                .toList();

        for (int index = 0; index < sortedCandidates.size(); index++) {
            String reportDate = normalizeDate(sortedCandidates.get(index).getStr("REPORT_DATE"));
            if (!oldestSelectedDate.equals(reportDate)) {
                continue;
            }
            if (index + 1 < sortedCandidates.size()) {
                String previousDate = normalizeDate(sortedCandidates.get(index + 1).getStr("REPORT_DATE"));
                if (StringUtils.hasText(previousDate)) {
                    reportDates.add(previousDate);
                }
            }
            break;
        }
        return reportDates.stream().toList();
    }

    Map<String, JSONObject> fetchPercentageRows(String eastCode, String companyType, ReportPeriod period) {
        String url = EASTMONEY_BFBBB_URL_TEMPLATE.formatted(eastCode, companyType);
        JSONObject root = JSONUtil.parseObj(httpGet(url));
        String key = period == ReportPeriod.ANNUAL ? "nd" : "bgq";
        return indexByReportDate(root.getJSONArray(key));
    }

    Map<String, JSONObject> fetchStatementRows(String url) {
        JSONObject root = JSONUtil.parseObj(httpGet(url));
        return indexByReportDate(root.getJSONArray("data"));
    }

    private List<JSONObject> fetchIndicatorReports(String eastCode) {
        JSONObject root = JSONUtil.parseObj(httpGet(EASTMONEY_ZYZB_URL_TEMPLATE.formatted(eastCode)));
        JSONArray data = root.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return List.of();
        }

        List<JSONObject> reports = new ArrayList<>();
        for (Object item : data) {
            if (item instanceof JSONObject report) {
                reports.add(report);
            }
        }
        return reports;
    }

    private String fetchCompanyType(String eastCode) {
        try {
            String html = httpGet(EASTMONEY_F10_INDEX_URL_TEMPLATE.formatted(eastCode));
            Matcher matcher = COMPANY_TYPE_PATTERN.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignored) {
            // Fallback to general enterprise type when the F10 page cannot be parsed.
        }
        return "4";
    }

    private JSONArray buildReports(List<JSONObject> indicatorReports,
                                   Map<String, JSONObject> percentageRows,
                                   Map<String, JSONObject> incomeRows,
                                   Map<String, JSONObject> balanceRows,
                                   Map<String, JSONObject> cashflowRows,
                                   ReportFocus focus) {
        JSONArray reports = new JSONArray();
        for (JSONObject indicatorReport : indicatorReports) {
            String reportDate = normalizeDate(indicatorReport.getStr("REPORT_DATE"));
            JSONObject percentageRow = percentageRows.get(reportDate);
            JSONObject incomeRow = incomeRows.get(reportDate);
            JSONObject balanceRow = balanceRows.get(reportDate);
            JSONObject previousBalanceRow = findPreviousRow(reportDate, balanceRows);
            JSONObject cashflowRow = cashflowRows.get(reportDate);

            JSONObject report = switch (focus) {
                case INCOME -> buildIncomeReport(indicatorReport, incomeRow);
                case BALANCE -> buildBalanceReport(indicatorReport, balanceRow, previousBalanceRow, percentageRow, incomeRow);
                case CASHFLOW -> buildCashflowReport(indicatorReport, cashflowRow);
                case CORE -> buildCoreReport(indicatorReport, percentageRow, incomeRow, balanceRow, previousBalanceRow, cashflowRow);
            };
            reports.add(report);
        }
        return reports;
    }

    private JSONObject buildBaseReport(JSONObject indicatorRow) {
        JSONObject report = new JSONObject();
        report.set("reportName", firstNonBlank(indicatorRow.getStr("REPORT_DATE_NAME"), indicatorRow.getStr("REPORT_TYPE")));
        report.set("reportType", indicatorRow.getStr("REPORT_TYPE"));
        report.set("reportDate", normalizeDate(indicatorRow.getStr("REPORT_DATE")));
        report.set("noticeDate", normalizeDate(indicatorRow.getStr("NOTICE_DATE")));
        return report;
    }

    private JSONObject buildCoreReport(JSONObject indicatorRow,
                                       JSONObject percentageRow,
                                       JSONObject incomeRow,
                                       JSONObject balanceRow,
                                       JSONObject previousBalanceRow,
                                       JSONObject cashflowRow) {
        JSONObject report = buildBaseReport(indicatorRow);
        BigDecimal revenue = firstDecimal(incomeRow, indicatorRow, "TOTAL_OPERATE_INCOME", "TOTALOPERATEREVE");
        BigDecimal operatingCost = firstDecimal(incomeRow, indicatorRow, "OPERATE_COST");
        BigDecimal netProfit = firstDecimal(cashflowRow, incomeRow, "NETPROFIT");
        BigDecimal parentNetProfit = firstDecimal(incomeRow, indicatorRow, "PARENT_NETPROFIT", "PARENTNETPROFIT");
        BigDecimal deductParentNetProfit = firstDecimal(incomeRow, indicatorRow, "DEDUCT_PARENT_NETPROFIT", "KCFJCXSYJLR");
        BigDecimal operatingCashflow = firstDecimal(cashflowRow, null, "NETCASH_OPERATE", "NETCASH_OPERATENOTE");
        BigDecimal cashReceivedFromSales = firstDecimal(cashflowRow, null, "SALES_SERVICES");
        BigDecimal totalAssets = firstDecimal(balanceRow, percentageRow, "TOTAL_ASSETS");
        BigDecimal totalLiabilities = firstDecimal(balanceRow, percentageRow, "TOTAL_LIABILITIES");
        BigDecimal totalParentEquity = firstDecimal(balanceRow, percentageRow, "TOTAL_PARENT_EQUITY", "PARENT_EQUITY_BALANCE");
        BigDecimal previousParentEquity = firstDecimal(previousBalanceRow, null, "TOTAL_PARENT_EQUITY", "PARENT_EQUITY_BALANCE");
        BigDecimal cashAndEquivalents = firstDecimal(cashflowRow, null, "END_CCE", "END_CASH");
        BigDecimal monetaryFunds = firstDecimal(balanceRow, percentageRow, "MONETARYFUNDS");
        BigDecimal interestBearingDebt = estimateInterestBearingDebt(balanceRow);
        BigDecimal inventory = firstDecimal(balanceRow, percentageRow, "INVENTORY");
        BigDecimal inventoryTurnover = estimateInventoryTurnover(indicatorRow, incomeRow, balanceRow, previousBalanceRow);
        BigDecimal inventoryDays = estimateInventoryDays(indicatorRow, inventoryTurnover);
        BigDecimal accountsPayable = firstDecimal(balanceRow, null, "ACCOUNTS_PAYABLE");
        BigDecimal notesPayableOperating = firstDecimal(balanceRow, null, "NOTE_ACCOUNTS_PAYABLE", "NOTE_PAYABLE");
        BigDecimal contractLiabilities = firstDecimal(balanceRow, null, "CONTRACT_LIAB", "ADVANCE_RECEIVABLES");
        BigDecimal staffSalaryPayable = firstDecimal(balanceRow, null, "STAFF_SALARY_PAYABLE");
        BigDecimal taxPayable = firstDecimal(balanceRow, null, "TAX_PAYABLE");
        BigDecimal otherPayable = firstDecimal(balanceRow, null, "TOTAL_OTHER_PAYABLE", "OTHER_PAYABLE");
        BigDecimal operatingLiabilities = estimateOperatingLiabilities(balanceRow);

        report.set("revenue", scale(revenue));
        report.set("revenueYoY", scale(firstDecimal(incomeRow, indicatorRow, "TOTAL_OPERATE_INCOME_YOY", "TOTALOPERATEREVETZ")));
        report.set("operatingCost", scale(operatingCost));
        report.set("netProfit", scale(netProfit));
        report.set("parentNetProfit", scale(parentNetProfit));
        report.set("parentNetProfitYoY", scale(firstDecimal(incomeRow, indicatorRow, "PARENT_NETPROFIT_YOY", "PARENTNETPROFITTZ")));
        report.set("deductParentNetProfit", scale(deductParentNetProfit));
        report.set("deductParentNetProfitYoY", scale(firstDecimal(incomeRow, indicatorRow, "DEDUCT_PARENT_NETPROFIT_YOY", "KCFJCXSYJLRTZ")));
        report.set("deductParentNetProfitRatio", scale(ratio(deductParentNetProfit, parentNetProfit)));
        report.set("roeWeighted", scale(firstDecimal(indicatorRow, null, "ROEJQ")));
        report.set("deductRoeWeighted", scale(firstDecimal(indicatorRow, null, "ROEKCJQ")));
        report.set("deductRoeEstimate", scale(estimateDeductRoe(deductParentNetProfit, totalParentEquity, previousParentEquity)));
        report.set("grossMargin", scale(firstDecimal(indicatorRow, null, "XSMLL")));
        report.set("netMargin", scale(firstDecimal(indicatorRow, percentageRow, "XSJLL", "SALE_NPR")));
        report.set("totalAssetTurnover", scale(firstDecimal(indicatorRow, percentageRow, "TOAZZL", "TOTAL_ASSETS_TR")));
        report.set("debtAssetRatio", scale(firstDecimal(indicatorRow, percentageRow, "ZCFZL", "DEBT_ASSET_RATIO")));
        report.set("salesCashReceived", scale(cashReceivedFromSales));
        report.set("cashSalesRatio", scale(ratio(cashReceivedFromSales, revenue)));
        report.set("operatingCashflow", scale(operatingCashflow));
        report.set("operatingCashflowToNetProfit", scale(ratio(operatingCashflow, netProfit)));
        report.set("totalAssets", scale(totalAssets));
        report.set("totalLiabilities", scale(totalLiabilities));
        report.set("totalParentEquity", scale(totalParentEquity));
        report.set("inventory", scale(inventory));
        report.set("inventoryTurnover", scale(inventoryTurnover));
        report.set("inventoryTurnoverDays", scale(inventoryDays));
        report.set("accountsPayable", scale(accountsPayable));
        report.set("notesPayableOperating", scale(notesPayableOperating));
        report.set("contractLiabilities", scale(contractLiabilities));
        report.set("staffSalaryPayable", scale(staffSalaryPayable));
        report.set("taxPayable", scale(taxPayable));
        report.set("otherPayable", scale(otherPayable));
        report.set("cashAndEquivalentsEnd", scale(cashAndEquivalents));
        report.set("monetaryFunds", scale(monetaryFunds));
        report.set("interestBearingDebtEstimate", scale(interestBearingDebt));
        report.set("interestBearingDebtToAsset", scale(ratio(interestBearingDebt, totalAssets)));
        report.set("operatingLiabilitiesEstimate", scale(operatingLiabilities));
        report.set("operatingLiabilitiesToAsset", scale(ratio(operatingLiabilities, totalAssets)));
        report.set("operatingLiabilitiesToTotalLiabilities", scale(ratio(operatingLiabilities, totalLiabilities)));
        report.set("accountsPayableTurnover", scale(firstDecimal(indicatorRow, null, "ACCOUNTS_PAYABLE_TR")));
        report.set("accountsPayableDays", scale(firstDecimal(indicatorRow, null, "PAYABLE_TDAYS")));
        report.set("operatingLiabilityBreakdown", buildOperatingLiabilityBreakdown(balanceRow));
        report.set("cashToDebt", scale(ratio(cashAndEquivalents, interestBearingDebt)));
        return report;
    }

    private JSONObject buildIncomeReport(JSONObject indicatorRow, JSONObject incomeRow) {
        JSONObject report = buildBaseReport(indicatorRow);
        report.set("revenue", scale(firstDecimal(incomeRow, indicatorRow, "TOTAL_OPERATE_INCOME", "TOTALOPERATEREVE")));
        report.set("revenueYoY", scale(firstDecimal(incomeRow, indicatorRow, "TOTAL_OPERATE_INCOME_YOY", "TOTALOPERATEREVETZ")));
        report.set("operatingCost", scale(firstDecimal(incomeRow, indicatorRow, "OPERATE_COST")));
        report.set("totalOperatingCost", scale(firstDecimal(incomeRow, null, "TOTAL_OPERATE_COST")));
        report.set("saleExpense", scale(firstDecimal(incomeRow, null, "SALE_EXPENSE")));
        report.set("manageExpense", scale(firstDecimal(incomeRow, null, "MANAGE_EXPENSE")));
        report.set("researchExpense", scale(firstDecimal(incomeRow, null, "RESEARCH_EXPENSE")));
        report.set("financeExpense", scale(firstDecimal(incomeRow, null, "FINANCE_EXPENSE")));
        report.set("operatingProfit", scale(firstDecimal(incomeRow, null, "OPERATE_PROFIT")));
        report.set("totalProfit", scale(firstDecimal(incomeRow, null, "TOTAL_PROFIT")));
        report.set("netProfit", scale(firstDecimal(incomeRow, null, "NETPROFIT")));
        report.set("parentNetProfit", scale(firstDecimal(incomeRow, indicatorRow, "PARENT_NETPROFIT", "PARENTNETPROFIT")));
        report.set("deductParentNetProfit", scale(firstDecimal(incomeRow, indicatorRow, "DEDUCT_PARENT_NETPROFIT", "KCFJCXSYJLR")));
        report.set("deductRoeWeighted", scale(firstDecimal(indicatorRow, null, "ROEKCJQ")));
        report.set("grossMargin", scale(firstDecimal(indicatorRow, null, "XSMLL")));
        report.set("netMargin", scale(firstDecimal(indicatorRow, null, "XSJLL")));
        return report;
    }

    private JSONObject buildBalanceReport(JSONObject indicatorRow,
                                          JSONObject balanceRow,
                                          JSONObject previousBalanceRow,
                                          JSONObject percentageRow,
                                          JSONObject incomeRow) {
        JSONObject report = buildBaseReport(indicatorRow);
        BigDecimal totalAssets = firstDecimal(balanceRow, percentageRow, "TOTAL_ASSETS");
        BigDecimal interestBearingDebt = estimateInterestBearingDebt(balanceRow);
        BigDecimal totalLiabilities = firstDecimal(balanceRow, percentageRow, "TOTAL_LIABILITIES");
        BigDecimal inventory = firstDecimal(balanceRow, percentageRow, "INVENTORY");
        BigDecimal accountsPayable = firstDecimal(balanceRow, null, "ACCOUNTS_PAYABLE");
        BigDecimal notesPayableOperating = firstDecimal(balanceRow, null, "NOTE_ACCOUNTS_PAYABLE", "NOTE_PAYABLE");
        BigDecimal contractLiabilities = firstDecimal(balanceRow, null, "CONTRACT_LIAB", "ADVANCE_RECEIVABLES");
        BigDecimal staffSalaryPayable = firstDecimal(balanceRow, null, "STAFF_SALARY_PAYABLE");
        BigDecimal taxPayable = firstDecimal(balanceRow, null, "TAX_PAYABLE");
        BigDecimal otherPayable = firstDecimal(balanceRow, null, "TOTAL_OTHER_PAYABLE", "OTHER_PAYABLE");
        BigDecimal operatingLiabilities = estimateOperatingLiabilities(balanceRow);
        BigDecimal inventoryTurnover = estimateInventoryTurnover(indicatorRow, incomeRow, balanceRow, previousBalanceRow);
        report.set("totalAssets", scale(totalAssets));
        report.set("totalLiabilities", scale(totalLiabilities));
        report.set("totalParentEquity", scale(firstDecimal(balanceRow, percentageRow, "TOTAL_PARENT_EQUITY", "PARENT_EQUITY_BALANCE")));
        report.set("totalCurrentAssets", scale(firstDecimal(balanceRow, percentageRow, "TOTAL_CURRENT_ASSETS")));
        report.set("totalCurrentLiabilities", scale(firstDecimal(balanceRow, percentageRow, "TOTAL_CURRENT_LIAB", "TOTAL_CURRENT_LIABILITIES")));
        report.set("totalNonCurrentAssets", scale(firstDecimal(balanceRow, percentageRow, "TOTAL_NONCURRENT_ASSETS")));
        report.set("totalNonCurrentLiabilities", scale(firstDecimal(balanceRow, percentageRow, "TOTAL_NONCURRENT_LIAB", "TOTAL_NONCURRENT_LIABILITIES")));
        report.set("monetaryFunds", scale(firstDecimal(balanceRow, percentageRow, "MONETARYFUNDS")));
        report.set("accountsReceivable", scale(firstDecimal(balanceRow, percentageRow, "ACCOUNTS_RECE")));
        report.set("inventory", scale(inventory));
        report.set("inventoryTurnover", scale(inventoryTurnover));
        report.set("inventoryTurnoverDays", scale(estimateInventoryDays(indicatorRow, inventoryTurnover)));
        report.set("inventoryToCurrentAssets", scale(ratio(inventory, firstDecimal(balanceRow, percentageRow, "TOTAL_CURRENT_ASSETS"))));
        report.set("accountsPayable", scale(accountsPayable));
        report.set("notesPayableOperating", scale(notesPayableOperating));
        report.set("contractLiabilities", scale(contractLiabilities));
        report.set("staffSalaryPayable", scale(staffSalaryPayable));
        report.set("taxPayable", scale(taxPayable));
        report.set("otherPayable", scale(otherPayable));
        report.set("fixedAsset", scale(firstDecimal(balanceRow, percentageRow, "FIXED_ASSET")));
        report.set("intangibleAsset", scale(firstDecimal(balanceRow, percentageRow, "INTANGIBLE_ASSET")));
        report.set("goodwill", scale(firstDecimal(balanceRow, percentageRow, "GOODWILL")));
        report.set("interestBearingDebtEstimate", scale(interestBearingDebt));
        report.set("interestBearingDebtToAsset", scale(ratio(interestBearingDebt, totalAssets)));
        report.set("debtAssetRatio", scale(firstDecimal(indicatorRow, percentageRow, "ZCFZL", "DEBT_ASSET_RATIO")));
        report.set("operatingLiabilitiesEstimate", scale(operatingLiabilities));
        report.set("operatingLiabilitiesToAsset", scale(ratio(operatingLiabilities, totalAssets)));
        report.set("operatingLiabilitiesToTotalLiabilities", scale(ratio(operatingLiabilities, totalLiabilities)));
        report.set("accountsPayableTurnover", scale(firstDecimal(indicatorRow, null, "ACCOUNTS_PAYABLE_TR")));
        report.set("accountsPayableDays", scale(firstDecimal(indicatorRow, null, "PAYABLE_TDAYS")));
        report.set("operatingLiabilityBreakdown", buildOperatingLiabilityBreakdown(balanceRow));
        return report;
    }

    private JSONObject buildCashflowReport(JSONObject indicatorRow, JSONObject cashflowRow) {
        JSONObject report = buildBaseReport(indicatorRow);
        report.set("salesCashReceived", scale(firstDecimal(cashflowRow, null, "SALES_SERVICES")));
        report.set("totalOperateInflow", scale(firstDecimal(cashflowRow, null, "TOTAL_OPERATE_INFLOW")));
        report.set("totalOperateOutflow", scale(firstDecimal(cashflowRow, null, "TOTAL_OPERATE_OUTFLOW")));
        report.set("operatingNetCashflow", scale(firstDecimal(cashflowRow, null, "NETCASH_OPERATE", "NETCASH_OPERATENOTE")));
        report.set("investingNetCashflow", scale(firstDecimal(cashflowRow, null, "NETCASH_INVEST")));
        report.set("financingNetCashflow", scale(firstDecimal(cashflowRow, null, "NETCASH_FINANCE")));
        report.set("cashAndEquivalentsBegin", scale(firstDecimal(cashflowRow, null, "BEGIN_CCE", "BEGIN_CASH")));
        report.set("cashAndEquivalentsEnd", scale(firstDecimal(cashflowRow, null, "END_CCE", "END_CASH")));
        report.set("dividendAndProfitPaid", scale(firstDecimal(cashflowRow, null, "ASSIGN_DIVIDEND_PORFIT")));
        report.set("taxPaid", scale(firstDecimal(cashflowRow, null, "PAY_ALL_TAX")));
        report.set("staffCashPaid", scale(firstDecimal(cashflowRow, null, "PAY_STAFF_CASH")));
        return report;
    }

    private JSONObject findPreviousRow(String currentReportDate, Map<String, JSONObject> rows) {
        if (!StringUtils.hasText(currentReportDate) || rows == null || rows.isEmpty()) {
            return null;
        }

        return rows.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()) && entry.getKey().compareTo(currentReportDate) < 0)
                .sorted(Map.Entry.<String, JSONObject>comparingByKey().reversed())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal estimateInterestBearingDebt(JSONObject balanceRow) {
        if (balanceRow == null || balanceRow.isEmpty()) {
            return null;
        }

        BigDecimal total = BigDecimal.ZERO;
        total = add(total, readDecimal(balanceRow, "SHORT_LOAN"));
        total = add(total, readDecimal(balanceRow, "BORROW_FUND"));
        total = add(total, readDecimal(balanceRow, "LOAN_PBC"));
        total = add(total, readDecimal(balanceRow, "BOND_PAYABLE"));
        total = add(total, readDecimal(balanceRow, "SHORT_BOND_PAYABLE"));
        total = add(total, readDecimal(balanceRow, "LONG_LOAN"));
        total = add(total, readDecimal(balanceRow, "LONG_PAYABLE"));
        total = add(total, readDecimal(balanceRow, "LEASE_LIAB"));
        return total.compareTo(BigDecimal.ZERO) == 0 ? null : total;
    }

    private BigDecimal estimateOperatingLiabilities(JSONObject balanceRow) {
        if (balanceRow == null || balanceRow.isEmpty()) {
            return null;
        }

        BigDecimal total = BigDecimal.ZERO;
        total = add(total, firstDecimal(balanceRow, null, "ACCOUNTS_PAYABLE"));
        total = add(total, firstDecimal(balanceRow, null, "NOTE_ACCOUNTS_PAYABLE", "NOTE_PAYABLE"));
        total = add(total, firstDecimal(balanceRow, null, "CONTRACT_LIAB", "ADVANCE_RECEIVABLES"));
        total = add(total, firstDecimal(balanceRow, null, "STAFF_SALARY_PAYABLE"));
        total = add(total, firstDecimal(balanceRow, null, "TAX_PAYABLE"));
        total = add(total, firstDecimal(balanceRow, null, "TOTAL_OTHER_PAYABLE", "OTHER_PAYABLE"));
        return total.compareTo(BigDecimal.ZERO) == 0 ? null : total;
    }

    private JSONObject buildOperatingLiabilityBreakdown(JSONObject balanceRow) {
        JSONObject breakdown = new JSONObject();
        breakdown.set("accountsPayable", scale(firstDecimal(balanceRow, null, "ACCOUNTS_PAYABLE")));
        breakdown.set("notesPayableOperating", scale(firstDecimal(balanceRow, null, "NOTE_ACCOUNTS_PAYABLE", "NOTE_PAYABLE")));
        breakdown.set("contractLiabilities", scale(firstDecimal(balanceRow, null, "CONTRACT_LIAB", "ADVANCE_RECEIVABLES")));
        breakdown.set("staffSalaryPayable", scale(firstDecimal(balanceRow, null, "STAFF_SALARY_PAYABLE")));
        breakdown.set("taxPayable", scale(firstDecimal(balanceRow, null, "TAX_PAYABLE")));
        breakdown.set("otherPayable", scale(firstDecimal(balanceRow, null, "TOTAL_OTHER_PAYABLE", "OTHER_PAYABLE")));
        breakdown.set("otherCurrentLiabilities", scale(firstDecimal(balanceRow, null, "OTHER_CURRENT_LIAB")));
        return breakdown;
    }

    private BigDecimal estimateInventoryTurnover(JSONObject indicatorRow,
                                                 JSONObject incomeRow,
                                                 JSONObject balanceRow,
                                                 JSONObject previousBalanceRow) {
        BigDecimal directValue = firstDecimal(indicatorRow, null, "CHZZL", "INVENTORY_TR", "INVENTORY_TURNOVER");
        if (directValue != null) {
            return directValue;
        }

        BigDecimal operatingCost = firstDecimal(incomeRow, indicatorRow, "OPERATE_COST");
        BigDecimal currentInventory = firstDecimal(balanceRow, null, "INVENTORY");
        BigDecimal previousInventory = firstDecimal(previousBalanceRow, null, "INVENTORY");
        return ratio(operatingCost, average(currentInventory, previousInventory));
    }

    private BigDecimal estimateInventoryDays(JSONObject indicatorRow, BigDecimal inventoryTurnover) {
        BigDecimal directValue = firstDecimal(indicatorRow, null, "CHZZTS", "INVENTORY_TDAYS");
        if (directValue != null) {
            return directValue;
        }
        if (inventoryTurnover == null || inventoryTurnover.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return BigDecimal.valueOf(365).divide(inventoryTurnover, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateDeductRoe(BigDecimal deductParentNetProfit,
                                         BigDecimal totalParentEquity,
                                         BigDecimal previousParentEquity) {
        return ratio(deductParentNetProfit, average(totalParentEquity, previousParentEquity));
    }

    private BigDecimal firstDecimal(JSONObject primary, JSONObject secondary, String... keys) {
        BigDecimal primaryValue = readDecimal(primary, keys);
        return primaryValue != null ? primaryValue : readDecimal(secondary, keys);
    }

    private BigDecimal readDecimal(JSONObject row, String... keys) {
        if (row == null || row.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key) || !row.containsKey(key)) {
                continue;
            }
            BigDecimal value = row.getBigDecimal(key);
            if (value != null) {
                return value;
            }
            Object rawValue = row.get(key);
            if (rawValue instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
        }
        return null;
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal add(BigDecimal left, BigDecimal right) {
        return right == null ? left : left.add(right);
    }

    private BigDecimal average(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return null;
        }
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private Map<String, JSONObject> indexByReportDate(JSONArray rows) {
        Map<String, JSONObject> indexedRows = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return indexedRows;
        }

        for (Object rowObject : rows) {
            if (!(rowObject instanceof JSONObject row)) {
                continue;
            }
            String reportDate = normalizeDate(row.getStr("REPORT_DATE"));
            if (StringUtils.hasText(reportDate)) {
                indexedRows.put(reportDate, row);
            }
        }
        return indexedRows;
    }

    private boolean matchesReportPeriod(JSONObject report, ReportPeriod period) {
        String reportDate = normalizeDate(report.getStr("REPORT_DATE"));
        return switch (period) {
            case ANNUAL -> reportDate.endsWith("-12-31");
            case LATEST, ALL -> true;
        };
    }

    private String httpGet(String url) {
        return HttpRequest.get(url)
                .header("Referer", "https://emweb.securities.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0")
                .charset(StandardCharsets.UTF_8)
                .timeout(10_000)
                .execute()
                .body();
    }

    private String normalizeDate(String value) {
        String normalized = normalizeDateTime(value);
        return normalized.length() >= 10 ? normalized.substring(0, 10) : normalized;
    }

    private String normalizeDateTime(String value) {
        return StringUtils.hasText(value) ? value.trim().replace('T', ' ') : "";
    }

    private String normalizeText(String value) {
        return String.valueOf(value)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean looksLikeStockTarget(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        String normalized = candidate.trim();
        if (normalized.length() < 2 || normalized.length() > 32) {
            return false;
        }
        String uppercase = normalized.toUpperCase(Locale.ROOT);
        if (ENGLISH_TICKER_STOP_WORDS.contains(uppercase)) {
            return false;
        }
        if (EXACT_STOCK_IDENTIFIER_PATTERN.matcher(uppercase).matches()) {
            return true;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (STOP_WORDS.stream().anyMatch(lower::contains)) {
            return false;
        }
        return normalized.matches(".*[\\u4e00-\\u9fa5]{2,16}.*");
    }

    private boolean containsAnyHint(String message, Set<String> hints) {
        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        return hints.stream().anyMatch(normalized::contains);
    }

    private boolean containsAnyAshareHint(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.contains("沪a")
                    || normalized.contains("深a")
                    || normalized.contains("北a")
                    || normalized.contains("创业板")
                    || normalized.contains("科创板")) {
                return true;
            }
        }
        return false;
    }

    private int scoreCandidate(ResolvedStock candidate, String normalizedQuery) {
        int score = 0;
        if (normalizeText(candidate.code()).equals(normalizedQuery)) {
            score += 120;
        }
        if (normalizeText(candidate.name()).equals(normalizedQuery)) {
            score += 160;
        }
        if (normalizeText(candidate.name()).contains(normalizedQuery) || normalizedQuery.contains(normalizeText(candidate.name()))) {
            score += 100;
        }
        if (candidate.marketPrefix().equals("SH") && candidate.code().startsWith("6")) {
            score += 4;
        }
        if (candidate.marketPrefix().equals("SZ") && !candidate.code().startsWith("6") && !candidate.code().startsWith("8")) {
            score += 4;
        }
        if (candidate.marketPrefix().equals("BJ") && (candidate.code().startsWith("8") || candidate.code().startsWith("4"))) {
            score += 4;
        }
        return score;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 5;
        }
        return Math.min(limit, 8);
    }

    private String inferExchangePrefix(String code, String quoteId) {
        if (StringUtils.hasText(quoteId)) {
            if (quoteId.startsWith("1.")) {
                return "SH";
            }
            if (quoteId.startsWith("0.")) {
                return code != null && (code.startsWith("8") || code.startsWith("4")) ? "BJ" : "SZ";
            }
        }
        if (!StringUtils.hasText(code)) {
            return null;
        }
        if (code.startsWith("6") || code.startsWith("9")) {
            return "SH";
        }
        if (code.startsWith("8") || code.startsWith("4")) {
            return "BJ";
        }
        return "SZ";
    }

    private String buildSecuCode(String code, String marketPrefix) {
        return code + "." + marketPrefix;
    }

    private String errorJson(String stockQuery, String message) {
        JSONObject result = new JSONObject();
        result.set("status", "ERROR");
        result.set("input", stockQuery);
        result.set("message", message);
        result.set("source", "东方财富 F10");
        return JSONUtil.toJsonPrettyStr(result);
    }

    record ResolvedStock(String code, String name, String marketPrefix, String eastCode, String secuCode) {

        JSONObject toJson() {
            JSONObject stock = new JSONObject();
            stock.set("code", code);
            stock.set("name", name);
            stock.set("market", marketPrefix);
            stock.set("eastCode", eastCode);
            stock.set("secuCode", secuCode);
            return stock;
        }
    }

    enum ReportPeriod {
        ANNUAL("annual"),
        LATEST("latest"),
        ALL("all");

        private final String code;

        ReportPeriod(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        static ReportPeriod from(String rawValue) {
            if (!StringUtils.hasText(rawValue)) {
                return ANNUAL;
            }
            return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
                case "latest", "recent", "newest", "最新" -> LATEST;
                case "all", "全部" -> ALL;
                default -> ANNUAL;
            };
        }
    }

    enum ReportFocus {
        CORE("core"),
        INCOME("income"),
        BALANCE("balance"),
        CASHFLOW("cashflow");

        private final String code;

        ReportFocus(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        static ReportFocus from(String rawValue) {
            if (!StringUtils.hasText(rawValue)) {
                return CORE;
            }
            return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
                case "income", "利润表" -> INCOME;
                case "balance", "balancesheet", "资产负债表" -> BALANCE;
                case "cashflow", "cash", "现金流" -> CASHFLOW;
                default -> CORE;
            };
        }
    }
}
