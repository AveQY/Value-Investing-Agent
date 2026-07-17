package com.aweqy.aiagent.service;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValueAnalysisServiceTest {

    private final Path memoryDir = Path.of("target", "test-chat-memory", "value-analysis-service");
    private final FileBasedChatMemory chatMemory = new FileBasedChatMemory(memoryDir.toAbsolutePath().toString());
    private final ValueAnalysisService valueAnalysisService = new ValueAnalysisService(
            mock(ChatModel.class),
            chatMemory,
            mock(FundAppLocalDocumentRetriever.class),
            new FinanceQuestionClassifier(),
            new ToolCallback[0],
            new ChatMemoryMetadataService(chatMemory)
    );

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(memoryDir)) {
            try (var paths = Files.walk(memoryDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
    }

    @Test
    void shouldNotPersistDirectReplyToChatMemoryWhenValueModeRejectsQuestion() {
        String chatId = "value_direct_reply_001";

        String answer = valueAnalysisService.analyze("介绍一下北京景点", chatId);

        Assertions.assertTrue(answer.contains("当前价值分析模式只支持单只股票或单家上市公司"));
        Assertions.assertFalse(chatMemory.exists(chatId));
    }

    @Test
    void shouldRejectNonStockTargetInValueMode() {
        String chatId = "value_non_stock_001";

        String answer = valueAnalysisService.analyze("分析一下沪深300指数", chatId);

        Assertions.assertTrue(answer.contains("只分析单只股票"));
        Assertions.assertTrue(chatMemory.exists(chatId));
        Assertions.assertEquals(answer, chatMemory.getAll(chatId).get(1).getText());
    }

    @Test
    void shouldRejectMultipleStocksInValueMode() {
        String chatId = "value_multi_stock_001";

        String answer = valueAnalysisService.analyze("对比贵州茅台和五粮液近三年毛利率", chatId);

        Assertions.assertTrue(answer.contains("每次只分析一只股票"));
        Assertions.assertTrue(answer.contains("贵州茅台"));
        Assertions.assertTrue(answer.contains("五粮液"));
        Assertions.assertTrue(chatMemory.exists(chatId));
        Assertions.assertEquals(answer, chatMemory.getAll(chatId).get(1).getText());
    }

    @Test
    void shouldUseFinancialReportToolAsPrimaryRealtimeEvidence() {
        ToolCallback stockPriceTool = mockTool("Get realtime stock quote", "{\"status\":\"SUCCESS\",\"tool\":\"quote\"}");
        ToolCallback financialReportTool = mockTool("Search A-share financial reports", "{\"status\":\"SUCCESS\",\"tool\":\"financial-report\"}");
        ToolCallback searchTool = mockTool("Search for information", "{\"status\":\"SUCCESS\",\"tool\":\"search\"}");
        ValueAnalysisService service = new ValueAnalysisService(
                mock(ChatModel.class),
                chatMemory,
                mock(FundAppLocalDocumentRetriever.class),
                new FinanceQuestionClassifier(),
                new ToolCallback[]{stockPriceTool, financialReportTool, searchTool},
                new ChatMemoryMetadataService(chatMemory)
        );

        Object preparation = ReflectionTestUtils.invokeMethod(
                service,
                "buildHeuristicPreparation",
                "请分析中国卫星近三年现金及其等价物/带息债务、资产负债率和带息债务/总资产"
        );
        @SuppressWarnings("unchecked")
        List<Object> toolResults = ReflectionTestUtils.invokeMethod(service, "collectRealtimeEvidence", preparation);

        List<String> toolNames = toolResults.stream()
                .map(result -> (String) ReflectionTestUtils.invokeMethod(result, "toolName"))
                .toList();

        Assertions.assertFalse(toolNames.isEmpty());
        Assertions.assertEquals("Get realtime stock quote", toolNames.get(0));
        Assertions.assertEquals("Search A-share financial reports", toolNames.get(1));
        Assertions.assertTrue(toolNames.contains("Search for information"));
    }

    @Test
    void shouldPlanFinancialReportToolWithThreeYearLimit() {
        Object preparation = ReflectionTestUtils.invokeMethod(
                valueAnalysisService,
                "buildHeuristicPreparation",
                "请分析中国卫星近三年现金及其等价物/带息债务、资产负债率和带息债务/总资产"
        );
        @SuppressWarnings("unchecked")
        Object plannedCall = ((List<Object>) ReflectionTestUtils.invokeMethod(
                valueAnalysisService,
                "buildInitialEvidenceCalls",
                preparation,
                ReflectionTestUtils.invokeMethod(valueAnalysisService, "deriveGapKeys", preparation, "请分析中国卫星近三年现金及其等价物/带息债务、资产负债率和带息债务/总资产"),
                "请分析中国卫星近三年现金及其等价物/带息债务、资产负债率和带息债务/总资产"
        )).stream()
                .filter(call -> "Search A-share financial reports".equals(ReflectionTestUtils.invokeMethod(call, "toolName")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) ReflectionTestUtils.invokeMethod(plannedCall, "arguments");
        Assertions.assertEquals(3, arguments.get("limit"));
        Assertions.assertEquals("annual", arguments.get("period"));
        Assertions.assertEquals("core", arguments.get("focus"));
    }

    @Test
    void shouldAddCurrentTimeToolWhenQuestionUsesRelativeDate() {
        ToolCallback currentTimeTool = mockTool("Get current time", "{\"status\":\"SUCCESS\",\"timezone\":\"UTC\"}");
        ToolCallback stockPriceTool = mockTool("Get realtime stock quote", "{\"status\":\"SUCCESS\",\"tool\":\"quote\"}");
        ToolCallback financialReportTool = mockTool("Search A-share financial reports", "{\"status\":\"SUCCESS\",\"tool\":\"financial-report\"}");
        ValueAnalysisService service = new ValueAnalysisService(
                mock(ChatModel.class),
                chatMemory,
                mock(FundAppLocalDocumentRetriever.class),
                new FinanceQuestionClassifier(),
                new ToolCallback[]{currentTimeTool, stockPriceTool, financialReportTool},
                new ChatMemoryMetadataService(chatMemory)
        );

        Object preparation = ReflectionTestUtils.invokeMethod(
                service,
                "buildHeuristicPreparation",
                "请分析中国卫星截至今天近三年现金及其等价物/带息债务"
        );
        @SuppressWarnings("unchecked")
        Object evidence = ReflectionTestUtils.invokeMethod(
                service,
                "collectRealtimeEvidence",
                preparation,
                "请分析中国卫星截至今天近三年现金及其等价物/带息债务"
        );
        @SuppressWarnings("unchecked")
        List<Object> toolResults = (List<Object>) ReflectionTestUtils.invokeMethod(evidence, "results");

        List<String> toolNames = toolResults.stream()
                .map(result -> (String) ReflectionTestUtils.invokeMethod(result, "toolName"))
                .toList();

        Assertions.assertTrue(toolNames.contains("Get current time"));
    }

    @Test
    void shouldStripRelativeTimeWordsFromValueTargetCandidate() {
        Object preparation = ReflectionTestUtils.invokeMethod(
                valueAnalysisService,
                "buildHeuristicPreparation",
                "请分析中国卫星截至今天近三年现金及其等价物/带息债务"
        );

        String target = (String) ReflectionTestUtils.invokeMethod(preparation, "target");
        String stockQuery = (String) ReflectionTestUtils.invokeMethod(preparation, "stockQuery");

        Assertions.assertEquals("中国卫星", target);
        Assertions.assertEquals("中国卫星", stockQuery);
    }

    @Test
    void shouldExposeNearThreeYearAndWeeklyMethodPromptRequirements() throws Exception {
        var field = ValueAnalysisService.class.getDeclaredField("VALUE_ANALYSIS_SYSTEM_PROMPT");
        field.setAccessible(true);
        String prompt = (String) field.get(null);

        Assertions.assertTrue(prompt.contains("近三年"));
        Assertions.assertTrue(prompt.contains("## 7. 财务健康判断"));
        Assertions.assertTrue(prompt.contains("## 8. 周行五步法"));
        Assertions.assertTrue(prompt.contains("## 最终投资建议"));
        Assertions.assertTrue(prompt.contains("闲钱"));
        Assertions.assertTrue(prompt.contains("必须先自行计算"));
    }

    @Test
    void shouldTreatDeductRoeAsSatisfiedWhenRawFieldsCanSupportEstimation() {
        ToolCallback stockPriceTool = mockTool("Get realtime stock quote", "{\"status\":\"SUCCESS\",\"tool\":\"quote\"}");
        ToolCallback financialReportTool = mockTool(
                "Search A-share financial reports",
                """
                        {
                          "status": "SUCCESS",
                          "reports": [
                            {
                              "reportDate": "2024-12-31",
                              "deductParentNetProfit": 18,
                              "totalParentEquity": 120
                            },
                            {
                              "reportDate": "2023-12-31",
                              "deductParentNetProfit": 16,
                              "totalParentEquity": 100
                            }
                          ]
                        }
                        """
        );
        ValueAnalysisService service = new ValueAnalysisService(
                mock(ChatModel.class),
                chatMemory,
                mock(FundAppLocalDocumentRetriever.class),
                new FinanceQuestionClassifier(),
                new ToolCallback[]{stockPriceTool, financialReportTool},
                new ChatMemoryMetadataService(chatMemory)
        );

        Object preparation = ReflectionTestUtils.invokeMethod(
                service,
                "buildHeuristicPreparation",
                "请分析中国卫星近三年扣非 ROE"
        );
        Object evidence = ReflectionTestUtils.invokeMethod(
                service,
                "collectRealtimeEvidence",
                preparation,
                "请分析中国卫星近三年扣非 ROE"
        );
        @SuppressWarnings("unchecked")
        List<String> missingData = (List<String>) ReflectionTestUtils.getField(evidence, "remainingMissingData");

        Assertions.assertFalse(missingData.contains("近三年扣非 ROE"));
    }

    @Test
    void shouldUseRawFieldQueriesWhenMetricNeedsContinuationSearch() {
        Object preparation = ReflectionTestUtils.invokeMethod(
                valueAnalysisService,
                "buildHeuristicPreparation",
                "请分析中国卫星近三年扣非 ROE 和存货周转率"
        );

        @SuppressWarnings("unchecked")
        List<Object> plannedCalls = (List<Object>) ReflectionTestUtils.invokeMethod(
                valueAnalysisService,
                "buildContinuationEvidenceCalls",
                preparation,
                "请分析中国卫星近三年扣非 ROE 和存货周转率",
                Set.of("deduct_roe", "inventory_turnover"),
                3
        );

        String deductQuery = plannedCalls.stream()
                .filter(call -> "近三年扣非 ROE 原始字段补查".equals(ReflectionTestUtils.invokeMethod(call, "focus")))
                .map(call -> (Map<String, Object>) ReflectionTestUtils.invokeMethod(call, "arguments"))
                .map(arguments -> String.valueOf(arguments.get("query")))
                .findFirst()
                .orElse("");
        String inventoryQuery = plannedCalls.stream()
                .filter(call -> "存货周转与经营性负债原始字段补查".equals(ReflectionTestUtils.invokeMethod(call, "focus")))
                .map(call -> (Map<String, Object>) ReflectionTestUtils.invokeMethod(call, "arguments"))
                .map(arguments -> String.valueOf(arguments.get("query")))
                .findFirst()
                .orElse("");

        Assertions.assertTrue(deductQuery.contains("归母净资产"));
        Assertions.assertTrue(deductQuery.contains("ROEKCJQ"));
        Assertions.assertTrue(inventoryQuery.contains("营业成本"));
        Assertions.assertTrue(inventoryQuery.contains("应付账款"));
    }

    private ToolCallback mockTool(String name, String output) {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getName()).thenReturn(name);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(output);
        return tool;
    }
}
