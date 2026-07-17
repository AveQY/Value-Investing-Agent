package com.aweqy.aiagent.app;

import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.FinanceQuestionClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundAppToolAgentLoopTest {

    @Test
    void shouldContinuePlanningWithAdditionalToolRoundsWhenInformationIsStillMissing() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallback financialTool = mockTool("Search A-share financial reports", "{\"status\":\"SUCCESS\",\"facts\":\"已拿到近5年财报\"}");
        ToolCallback searchTool = mockTool("Search for information", "{\"status\":\"SUCCESS\",\"facts\":\"已拿到最新公告\"}");
        FundApp fundApp = buildFundApp(chatModel, financialTool, searchTool);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search A-share financial reports","arguments":{"stockQuery":"贵州茅台","limit":5,"period":"annual","focus":"core"}}]}
                        """),
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search for information","arguments":{"query":"贵州茅台 最新公告 经营变化"}}]}
                        """),
                chatResponse("""
                        {"needTool":false,"calls":[]}
                        """),
                chatResponse("这是循环补查后的最终回答")
        );

        String answer = fundApp.doChatWithTools("请分析贵州茅台最近5年财报，如果还缺信息就继续查最新公告", "tool_loop_001");

        assertEquals("这是循环补查后的最终回答", answer);
        verify(financialTool, times(1)).call(anyString());
        verify(searchTool, times(1)).call(anyString());
    }

    @Test
    void shouldStopWhenContinuationPlannerRepeatsExistingToolCall() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallback financialTool = mockTool("Search A-share financial reports", "{\"status\":\"SUCCESS\",\"facts\":\"已拿到近5年财报\"}");
        ToolCallback searchTool = mockTool("Search for information", "{\"status\":\"SUCCESS\",\"facts\":\"不会被调用\"}");
        FundApp fundApp = buildFundApp(chatModel, financialTool, searchTool);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search A-share financial reports","arguments":{"stockQuery":"中国卫星","limit":5,"period":"annual","focus":"core"}}]}
                        """),
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search A-share financial reports","arguments":{"focus":"core","period":"annual","limit":5,"stockQuery":"中国卫星"}}]}
                        """),
                chatResponse("这是去重后的最终回答")
        );

        String answer = fundApp.doChatWithTools("请分析中国卫星最近5年财报", "tool_loop_002");

        assertEquals("这是去重后的最终回答", answer);
        verify(financialTool, times(1)).call(anyString());
        verify(searchTool, never()).call(anyString());
    }

    @Test
    void shouldContinueAnsweringOriginalQuestionAfterResolvingCurrentTimeInRagMode() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallback currentTimeTool = mockTool(
                "Get current time",
                """
                        {"status":"SUCCESS","currentTime":"2026-04-22 09:30:00","timezone":"America/New_York","offset":"-04:00"}
                        """
        );
        FundApp fundApp = buildFundApp(chatModel, currentTimeTool);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Get current time","arguments":{"timezoneHint":"AAPL 截至今天美股是否已开盘"}}]}
                        """),
                chatResponse("截至 America/New_York 时间 2026-04-22 09:30:00，美股当日尚未开盘。")
        );

        String answer = fundApp.doChatWithRag("AAPL 截至今天美股是否已开盘", "rag_time_001");

        assertEquals("截至 America/New_York 时间 2026-04-22 09:30:00，美股当日尚未开盘。", answer);
        verify(currentTimeTool, times(1)).call(anyString());
    }

    @Test
    void shouldRespectToolAgentRoundLimitOfFiveRounds() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallback searchTool = mockTool("Search for information", "{\"status\":\"SUCCESS\",\"facts\":\"补查完成\"}");
        FundApp fundApp = buildFundApp(chatModel, searchTool);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search for information","arguments":{"query":"贵州茅台 第一轮"}}]}
                        """),
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search for information","arguments":{"query":"贵州茅台 第二轮"}}]}
                        """),
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search for information","arguments":{"query":"贵州茅台 第三轮"}}]}
                        """),
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search for information","arguments":{"query":"贵州茅台 第四轮"}}]}
                        """),
                chatResponse("""
                        {"needTool":true,"calls":[{"tool":"Search for information","arguments":{"query":"贵州茅台 第五轮"}}]}
                        """),
                chatResponse("达到上限后的最终回答")
        );

        String answer = fundApp.doChatWithTools("持续补查贵州茅台信息", "tool_loop_003");

        assertEquals("达到上限后的最终回答", answer);
        verify(searchTool, times(5)).call(anyString());
        verify(chatModel, times(6)).call(any(Prompt.class));
    }

    private FundApp buildFundApp(ChatModel chatModel, ToolCallback... tools) {
        FundAppLocalDocumentRetriever localDocumentRetriever = mock(FundAppLocalDocumentRetriever.class);
        when(localDocumentRetriever.search(anyString(), any(Integer.class))).thenReturn(List.of());
        FundApp fundApp = new FundApp(
                chatModel,
                mock(ChatMemory.class),
                localDocumentRetriever,
                new FinanceQuestionClassifier(),
                new ChatMemoryMetadataService(mock(ChatMemory.class))
        );
        ReflectionTestUtils.setField(fundApp, "allTools", tools);
        return fundApp;
    }

    private ToolCallback mockTool(String name, String output) {
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn(name + " description");
        when(tool.getInputTypeSchema()).thenReturn("{}");
        when(tool.call(anyString())).thenReturn(output);
        return tool;
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
