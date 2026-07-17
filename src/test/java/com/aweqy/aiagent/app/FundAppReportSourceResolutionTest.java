package com.aweqy.aiagent.app;

import com.aweqy.aiagent.model.AdvisorStyle;
import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.ChatModelExecutionService;
import com.aweqy.aiagent.service.ChatModelRegistryService;
import com.aweqy.aiagent.service.FinanceQuestionClassifier;
import com.aweqy.aiagent.service.ReportDocumentService;
import com.aweqy.aiagent.service.ValueAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FundAppReportSourceResolutionTest {

    @Test
    void shouldReuseProvidedReportSourceContent() {
        ValueAnalysisService valueAnalysisService = mock(ValueAnalysisService.class);
        FundApp fundApp = buildFundApp(valueAnalysisService);

        String sourceAnswer = ReflectionTestUtils.invokeMethod(
                fundApp,
                "resolveReportSourceAnswer",
                "请分析宁德时代",
                "chat_report_001",
                AdvisorStyle.DEFAULT,
                "mock-model",
                "  已有完整回答  ",
                "value"
        );

        assertEquals("已有完整回答", sourceAnswer);
        verifyNoInteractions(valueAnalysisService);
    }

    @Test
    void shouldUseValueAnalysisFlowWhenReportModeIsValue() {
        ValueAnalysisService valueAnalysisService = mock(ValueAnalysisService.class);
        FundApp fundApp = buildFundApp(valueAnalysisService);

        doReturn("价值分析回答").when(valueAnalysisService)
                .analyze("请分析贵州茅台", "chat_report_002", "buffett_framework", "mock-model");

        String sourceAnswer = ReflectionTestUtils.invokeMethod(
                fundApp,
                "resolveReportSourceAnswer",
                "请分析贵州茅台",
                "chat_report_002",
                AdvisorStyle.BUFFETT_FRAMEWORK,
                "mock-model",
                null,
                "value"
        );

        assertEquals("价值分析回答", sourceAnswer);
        verify(valueAnalysisService).analyze("请分析贵州茅台", "chat_report_002", "buffett_framework", "mock-model");
    }

    @Test
    void shouldUseToolsFlowWhenReportModeIsTools() {
        ValueAnalysisService valueAnalysisService = mock(ValueAnalysisService.class);
        FundApp fundApp = org.mockito.Mockito.spy(buildFundApp(valueAnalysisService));

        doReturn("工具增强回答").when(fundApp)
                .doChatWithTools("请分析中国卫星", "chat_report_003", "default", "mock-model");

        String sourceAnswer = ReflectionTestUtils.invokeMethod(
                fundApp,
                "resolveReportSourceAnswer",
                "请分析中国卫星",
                "chat_report_003",
                AdvisorStyle.DEFAULT,
                "mock-model",
                null,
                "tools"
        );

        assertEquals("工具增强回答", sourceAnswer);
        verify(fundApp).doChatWithTools("请分析中国卫星", "chat_report_003", "default", "mock-model");
        verifyNoInteractions(valueAnalysisService);
    }

    @Test
    void shouldKeepBuffettStyleWhenReportModeIsTools() {
        ValueAnalysisService valueAnalysisService = mock(ValueAnalysisService.class);
        FundApp fundApp = org.mockito.Mockito.spy(buildFundApp(valueAnalysisService));

        doReturn("工具增强回答").when(fundApp)
                .doChatWithTools("请分析贵州茅台", "chat_report_004", "buffett_framework", "mock-model");

        String sourceAnswer = ReflectionTestUtils.invokeMethod(
                fundApp,
                "resolveReportSourceAnswer",
                "请分析贵州茅台",
                "chat_report_004",
                AdvisorStyle.BUFFETT_FRAMEWORK,
                "mock-model",
                null,
                "tools"
        );

        assertEquals("工具增强回答", sourceAnswer);
        verify(fundApp).doChatWithTools("请分析贵州茅台", "chat_report_004", "buffett_framework", "mock-model");
        verifyNoInteractions(valueAnalysisService);
    }

    private FundApp buildFundApp(ValueAnalysisService valueAnalysisService) {
        ChatMemory chatMemory = mock(ChatMemory.class);
        return new FundApp(
                chatMemory,
                mock(FundAppLocalDocumentRetriever.class),
                new FinanceQuestionClassifier(),
                new ChatMemoryMetadataService(chatMemory),
                new ChatModelExecutionService(),
                mock(ChatModelRegistryService.class),
                mock(ReportDocumentService.class),
                valueAnalysisService
        );
    }
}
