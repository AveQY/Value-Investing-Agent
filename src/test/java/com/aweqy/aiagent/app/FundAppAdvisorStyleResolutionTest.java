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
import static org.mockito.Mockito.mock;

class FundAppAdvisorStyleResolutionTest {

    @Test
    void shouldFallbackBuffettStyleInGeneralModes() {
        FundApp fundApp = buildFundApp();

        AdvisorStyle style = ReflectionTestUtils.invokeMethod(
                fundApp,
                "resolveGeneralModeAdvisorStyle",
                "buffett_framework",
                "rag"
        );

        assertEquals(AdvisorStyle.DEFAULT, style);
    }

    @Test
    void shouldKeepBuffettStyleInToolsMode() {
        FundApp fundApp = buildFundApp();

        AdvisorStyle style = ReflectionTestUtils.invokeMethod(
                fundApp,
                "resolveToolsModeAdvisorStyle",
                "buffett_framework",
                "tools"
        );

        assertEquals(AdvisorStyle.BUFFETT_FRAMEWORK, style);
    }

    @Test
    void shouldKeepBuffettStyleForToolsReportSource() {
        FundApp fundApp = buildFundApp();

        AdvisorStyle style = ReflectionTestUtils.invokeMethod(
                fundApp,
                "resolveReportSourceAdvisorStyle",
                "buffett_framework",
                "tools"
        );

        assertEquals(AdvisorStyle.BUFFETT_FRAMEWORK, style);
    }

    private FundApp buildFundApp() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        return new FundApp(
                chatMemory,
                mock(FundAppLocalDocumentRetriever.class),
                new FinanceQuestionClassifier(),
                new ChatMemoryMetadataService(chatMemory),
                new ChatModelExecutionService(),
                mock(ChatModelRegistryService.class),
                mock(ReportDocumentService.class),
                mock(ValueAnalysisService.class)
        );
    }
}
