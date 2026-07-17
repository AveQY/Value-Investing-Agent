package com.aweqy.aiagent.app;

import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.FinanceQuestionClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundAppFinancialReportRoutingTest {

    @Test
    void shouldRouteAshareFinancialQuestionToFinancialReportToolWithoutGenericSearch() {
        FundApp fundApp = buildFundAppWithTools();

        List<String> toolNames = heuristicToolNames(
                fundApp,
                "请分析中国卫星最近5年现金及其等价物/带息债务、资产负债率和带息债务/总资产"
        );

        assertTrue(toolNames.contains("Search A-share financial reports"));
        assertFalse(toolNames.contains("Search for information"));
    }

    @Test
    void shouldKeepSearchToolForAnnouncementQueriesAlongsideFinancialReports() {
        FundApp fundApp = buildFundAppWithTools();

        List<String> toolNames = heuristicToolNames(
                fundApp,
                "请分析贵州茅台最近5年财报，并看看最新公告里有没有经营变化"
        );

        assertTrue(toolNames.contains("Search A-share financial reports"));
        assertTrue(toolNames.contains("Search for information"));
    }

    @Test
    void shouldRouteCurrentTimeQuestionToCurrentTimeTool() {
        FundApp fundApp = buildFundAppWithTools();

        List<String> toolNames = heuristicToolNames(
                fundApp,
                "纽约现在几点，今天是周几"
        );

        assertTrue(toolNames.contains("Get current time"));
    }

    private FundApp buildFundAppWithTools() {
        FundApp fundApp = new FundApp(
                mock(ChatModel.class),
                mock(ChatMemory.class),
                mock(FundAppLocalDocumentRetriever.class),
                new FinanceQuestionClassifier(),
                new ChatMemoryMetadataService(mock(ChatMemory.class))
        );
        ReflectionTestUtils.setField(fundApp, "allTools", new ToolCallback[]{
                mockTool("Get current time"),
                mockTool("Get realtime stock quote"),
                mockTool("Search A-share financial reports"),
                mockTool("Search for information")
        });
        return fundApp;
    }

    @SuppressWarnings("unchecked")
    private List<String> heuristicToolNames(FundApp fundApp, String message) {
        List<Object> plannedCalls = ReflectionTestUtils.invokeMethod(fundApp, "buildHeuristicToolCalls", message);
        return plannedCalls.stream()
                .map(call -> (String) ReflectionTestUtils.invokeMethod(call, "toolName"))
                .toList();
    }

    private ToolCallback mockTool(String name) {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getName()).thenReturn(name);
        return tool;
    }
}
