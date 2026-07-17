package com.aweqy.aiagent.tools;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AShareFinancialReportToolTest {

    private final AShareFinancialReportTool tool = new AShareFinancialReportTool();

    @Test
    void extractTargetCandidateShouldStripFinancialNoise() {
        assertEquals("中国卫星", tool.extractTargetCandidate("请分析中国卫星最近5年现金及其等价物/带息债务、资产负债率和带息债务/总资产"));
        assertEquals("600519", tool.extractTargetCandidate("帮我查下 600519 最新财报"));
    }

    @Test
    void resolveDirectSymbolShouldSupportCommonAshareFormats() {
        AShareFinancialReportTool.ResolvedStock byCode = tool.resolveDirectSymbol("600519");
        AShareFinancialReportTool.ResolvedStock byEastCode = tool.resolveDirectSymbol("SH600519");
        AShareFinancialReportTool.ResolvedStock bySecuCode = tool.resolveDirectSymbol("600519.SH");

        assertNotNull(byCode);
        assertNotNull(byEastCode);
        assertNotNull(bySecuCode);
        assertEquals("SH", byCode.marketPrefix());
        assertEquals("SH600519", byCode.eastCode());
        assertEquals("600519.SH", byCode.secuCode());
        assertEquals("600519", byEastCode.code());
        assertEquals("SH600519", bySecuCode.eastCode());
    }

    @Test
    void parseSearchResponseShouldKeepOnlyAshareCandidates() {
        String responseBody = """
                {
                  "QuotationCodeTable": {
                    "Data": [
                      {
                        "Classify": "AStock",
                        "SecurityTypeName": "沪A",
                        "SecurityType": "2",
                        "QuoteID": "1.600519",
                        "Code": "600519",
                        "Name": "贵州茅台"
                      },
                      {
                        "Classify": "UsStock",
                        "SecurityTypeName": "美股",
                        "SecurityType": "6",
                        "QuoteID": "105.AAPL",
                        "Code": "AAPL",
                        "Name": "Apple"
                      }
                    ]
                  }
                }
                """;

        List<AShareFinancialReportTool.ResolvedStock> candidates = tool.parseSearchResponse(responseBody);

        assertEquals(1, candidates.size());
        assertEquals("600519", candidates.get(0).code());
        assertEquals("贵州茅台", candidates.get(0).name());
        assertEquals("SH600519", candidates.get(0).eastCode());
    }

    @Test
    void selectReportsShouldKeepRecentAnnualReports() {
        JSONObject y2024 = new JSONObject().set("REPORT_DATE", "2024-12-31");
        JSONObject q32024 = new JSONObject().set("REPORT_DATE", "2024-09-30");
        JSONObject y2023 = new JSONObject().set("REPORT_DATE", "2023-12-31");

        List<JSONObject> reports = tool.selectReports(
                List.of(q32024, y2023, y2024),
                AShareFinancialReportTool.ReportPeriod.ANNUAL,
                2
        );

        assertEquals(2, reports.size());
        assertEquals("2024-12-31", reports.get(0).getStr("REPORT_DATE"));
        assertEquals("2023-12-31", reports.get(1).getStr("REPORT_DATE"));
    }

    @Test
    void buildCoreReportShouldAssembleDerivedRatios() {
        JSONObject indicatorRow = new JSONObject()
                .set("REPORT_DATE_NAME", "2024年报")
                .set("REPORT_DATE", "2024-12-31")
                .set("NOTICE_DATE", "2025-03-30")
                .set("ROEJQ", new BigDecimal("15.2"))
                .set("ROEKCJQ", new BigDecimal("14.8"))
                .set("XSMLL", new BigDecimal("42.6"))
                .set("XSJLL", new BigDecimal("20.1"))
                .set("TOAZZL", new BigDecimal("0.55"))
                .set("ZCFZL", new BigDecimal("50"));
        JSONObject incomeRow = new JSONObject()
                .set("TOTAL_OPERATE_INCOME", new BigDecimal("100"))
                .set("OPERATE_COST", new BigDecimal("120"))
                .set("PARENT_NETPROFIT", new BigDecimal("20"))
                .set("DEDUCT_PARENT_NETPROFIT", new BigDecimal("18"))
                .set("NETPROFIT", new BigDecimal("20"));
        JSONObject balanceRow = new JSONObject()
                .set("TOTAL_ASSETS", new BigDecimal("200"))
                .set("TOTAL_LIABILITIES", new BigDecimal("100"))
                .set("TOTAL_PARENT_EQUITY", new BigDecimal("120"))
                .set("MONETARYFUNDS", new BigDecimal("60"))
                .set("INVENTORY", new BigDecimal("50"))
                .set("ACCOUNTS_PAYABLE", new BigDecimal("25"))
                .set("NOTE_ACCOUNTS_PAYABLE", new BigDecimal("5"))
                .set("CONTRACT_LIAB", new BigDecimal("8"))
                .set("STAFF_SALARY_PAYABLE", new BigDecimal("3"))
                .set("TAX_PAYABLE", new BigDecimal("4"))
                .set("OTHER_PAYABLE", new BigDecimal("6"))
                .set("SHORT_LOAN", new BigDecimal("30"))
                .set("BOND_PAYABLE", new BigDecimal("10"));
        JSONObject previousBalanceRow = new JSONObject()
                .set("INVENTORY", new BigDecimal("30"))
                .set("TOTAL_PARENT_EQUITY", new BigDecimal("100"));
        JSONObject cashflowRow = new JSONObject()
                .set("SALES_SERVICES", new BigDecimal("90"))
                .set("NETCASH_OPERATE", new BigDecimal("24"))
                .set("NETPROFIT", new BigDecimal("20"))
                .set("END_CCE", new BigDecimal("50"));

        JSONObject report = ReflectionTestUtils.invokeMethod(
                tool,
                "buildCoreReport",
                indicatorRow,
                null,
                incomeRow,
                balanceRow,
                previousBalanceRow,
                cashflowRow
        );

        assertNotNull(report);
        assertEquals("2024-12-31", report.getStr("reportDate"));
        assertBigDecimalEquals("0.9", report.getBigDecimal("cashSalesRatio"));
        assertBigDecimalEquals("1.2", report.getBigDecimal("operatingCashflowToNetProfit"));
        assertBigDecimalEquals("0.9", report.getBigDecimal("deductParentNetProfitRatio"));
        assertBigDecimalEquals("14.8", report.getBigDecimal("deductRoeWeighted"));
        assertBigDecimalEquals("0.1636", report.getBigDecimal("deductRoeEstimate"));
        assertBigDecimalEquals("40", report.getBigDecimal("interestBearingDebtEstimate"));
        assertBigDecimalEquals("0.2", report.getBigDecimal("interestBearingDebtToAsset"));
        assertBigDecimalEquals("3", report.getBigDecimal("inventoryTurnover"));
        assertBigDecimalEquals("121.6667", report.getBigDecimal("inventoryTurnoverDays"));
        assertBigDecimalEquals("120", report.getBigDecimal("totalParentEquity"));
        assertBigDecimalEquals("25", report.getBigDecimal("accountsPayable"));
        assertBigDecimalEquals("5", report.getBigDecimal("notesPayableOperating"));
        assertBigDecimalEquals("8", report.getBigDecimal("contractLiabilities"));
        assertBigDecimalEquals("51", report.getBigDecimal("operatingLiabilitiesEstimate"));
        assertBigDecimalEquals("0.255", report.getBigDecimal("operatingLiabilitiesToAsset"));
        assertBigDecimalEquals("0.51", report.getBigDecimal("operatingLiabilitiesToTotalLiabilities"));
        assertBigDecimalEquals("1.25", report.getBigDecimal("cashToDebt"));
        assertTrue(report.containsKey("cashAndEquivalentsEnd"));
        JSONObject breakdown = report.getJSONObject("operatingLiabilityBreakdown");
        assertNotNull(breakdown);
        assertBigDecimalEquals("25", breakdown.getBigDecimal("accountsPayable"));
        assertBigDecimalEquals("5", breakdown.getBigDecimal("notesPayableOperating"));
        assertBigDecimalEquals("8", breakdown.getBigDecimal("contractLiabilities"));
    }

    private void assertBigDecimalEquals(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
