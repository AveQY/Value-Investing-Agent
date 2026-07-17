package com.aweqy.aiagent.app;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.FinanceQuestionClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundAppDirectReplyMemoryTest {

    private static final String EXPECTED_OUT_OF_DOMAIN_REPLY =
            "当前投资顾问仅支持财经与投资相关问题，例如股票、基金、指数、行情、公告、财报、估值和资产配置。请改问财经问题后再试；如果你想问个股，请尽量补充股票代码、市场或具体金融上下文。";

    private final Path memoryDir = Path.of("target", "test-chat-memory", "fund-app-direct-reply");
    private final FileBasedChatMemory chatMemory = new FileBasedChatMemory(memoryDir.toAbsolutePath().toString());
    private final FundApp fundApp = new FundApp(
            mock(ChatModel.class),
            chatMemory,
            mock(FundAppLocalDocumentRetriever.class),
            new FinanceQuestionClassifier(),
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
    void shouldNotPersistDirectReplyToChatMemoryWhenToolsModeRejectsQuestion() {
        String chatId = "tools_direct_reply_001";

        String answer = fundApp.doChatWithTools("帮我写一个贪吃蛇小游戏", chatId);

        Assertions.assertEquals(EXPECTED_OUT_OF_DOMAIN_REPLY, answer);
        Assertions.assertFalse(chatMemory.exists(chatId));
    }

    @Test
    void shouldNotPersistDirectReplyToChatMemoryWhenChatModeRejectsQuestion() {
        String chatId = "chat_direct_reply_001";

        String answer = fundApp.doChat("帮我写一个贪吃蛇小游戏", chatId);

        Assertions.assertEquals(EXPECTED_OUT_OF_DOMAIN_REPLY, answer);
        Assertions.assertFalse(chatMemory.exists(chatId));
    }

    @Test
    void shouldNotPersistDirectReplyToChatMemoryWhenRagModeRejectsQuestion() {
        String chatId = "rag_direct_reply_001";

        String answer = fundApp.doChatWithRag("介绍一下北京的景点", chatId);

        Assertions.assertEquals(EXPECTED_OUT_OF_DOMAIN_REPLY, answer);
        Assertions.assertFalse(chatMemory.exists(chatId));
    }

    @Test
    void shouldNotPersistDirectReplyToChatMemoryWhenReportModeRejectsQuestion() {
        String chatId = "report_direct_reply_001";

        FundApp.FundReport report = fundApp.doChatWithReport("写一篇端午节作文", chatId);

        Assertions.assertEquals("仅支持财经问题", report.title());
        Assertions.assertFalse(chatMemory.exists(chatId));
    }

    @Test
    void shouldAllowFinanceFollowUpWhenChatContextIsFinance() {
        ChatModel chatModel = mock(ChatModel.class);
        FundAppLocalDocumentRetriever localDocumentRetriever = mock(FundAppLocalDocumentRetriever.class);
        FundApp contextualFundApp = new FundApp(
                chatModel,
                chatMemory,
                localDocumentRetriever,
                new FinanceQuestionClassifier(),
                new ChatMemoryMetadataService(chatMemory)
        );
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"financeRelated\":true,\"label\":\"finance\",\"reason\":\"这是对前文个股分析的追问\"}"
                )))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是上下文财经追问回答"))))
        );
        when(localDocumentRetriever.search(any(), any(Integer.class))).thenReturn(List.of());

        String chatId = "chat_context_finance_001";
        chatMemory.add(chatId, List.of(
                new UserMessage("请分析中国卫星"),
                new AssistantMessage("可以，我们先拆财报与估值。")
        ));

        String answer = contextualFundApp.doChatWithRag("那现在能买吗", chatId);

        Assertions.assertEquals("这是上下文财经追问回答", answer);
    }
}
