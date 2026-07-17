package com.aweqy.aiagent.app;

import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.FinanceQuestionClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FundAppToolsDomainGuardTest {

    private static final String EXPECTED_OUT_OF_DOMAIN_REPLY =
            "当前投资顾问仅支持财经与投资相关问题，例如股票、基金、指数、行情、公告、财报、估值和资产配置。请改问财经问题后再试；如果你想问个股，请尽量补充股票代码、市场或具体金融上下文。";

    private final FundApp fundApp = new FundApp(
            mock(ChatModel.class),
            mock(ChatMemory.class),
            mock(FundAppLocalDocumentRetriever.class),
            new FinanceQuestionClassifier(),
            new ChatMemoryMetadataService(mock(ChatMemory.class))
    );

    @Test
    void shouldRejectNonFinanceQuestionInToolsMode() {
        String answer = fundApp.doChatWithTools("帮我写一个贪吃蛇小游戏", "chat-tools-001");

        assertEquals(EXPECTED_OUT_OF_DOMAIN_REPLY, answer);
    }

    @Test
    void shouldRejectNonFinanceQuestionInChatMode() {
        String answer = fundApp.doChat("帮我写一个贪吃蛇小游戏", "chat-001");

        assertEquals(EXPECTED_OUT_OF_DOMAIN_REPLY, answer);
    }

    @Test
    void shouldRejectNonFinanceQuestionInRagMode() {
        String answer = fundApp.doChatWithRag("介绍一下北京的景点", "chat-rag-001");

        assertEquals(EXPECTED_OUT_OF_DOMAIN_REPLY, answer);
    }

    @Test
    void shouldRejectNonFinanceQuestionInReportMode() {
        FundApp.FundReport report = fundApp.doChatWithReport("写一篇端午节作文", "chat-report-001");

        assertEquals("仅支持财经问题", report.title());
        assertEquals(2, report.suggestions().size());
    }

    @Test
    void shouldRecognizeFinanceQuestions() {
        assertTrue(fundApp.isFinanceRelatedQuestion("AAPL"));
        assertTrue(fundApp.isFinanceRelatedQuestion("帮我看下 AAPL 现在股价"));
        assertTrue(fundApp.isFinanceRelatedQuestion("比亚迪最新财报怎么看"));
        assertTrue(fundApp.isFinanceRelatedQuestion("600519"));
        assertTrue(fundApp.isFinanceRelatedQuestion("中国卫星"));
        assertTrue(fundApp.isFinanceRelatedQuestion("请分析一下贵州茅台"));
        assertTrue(fundApp.isFinanceRelatedQuestion("请分析中国卫星最近5年现金及其等价物/带息债务、资产负债率和带息债务/总资产"));
        assertTrue(fundApp.isFinanceRelatedQuestion("腾讯能不能买"));
        assertTrue(fundApp.isFinanceRelatedQuestion("帮我拆解一下宁德时代的护城河"));
    }

    @Test
    void shouldFilterOutNonFinanceQuestions() {
        assertFalse(fundApp.isFinanceRelatedQuestion("帮我写一个冒泡排序"));
        assertFalse(fundApp.isFinanceRelatedQuestion("介绍一下北京的景点"));
        assertFalse(fundApp.isFinanceRelatedQuestion("写一个 Java PDF 导出工具"));
        assertFalse(fundApp.isFinanceRelatedQuestion("分析一下苹果的营养价值"));
    }
}
