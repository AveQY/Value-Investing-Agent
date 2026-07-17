package com.aweqy.aiagent.service;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceConversationGuardTest {

    private final Path memoryDir = Path.of("target", "test-chat-memory", "finance-conversation-guard");
    private final FileBasedChatMemory chatMemory = new FileBasedChatMemory(memoryDir.toAbsolutePath().toString());
    private final ChatModel chatModel = mock(ChatModel.class);
    private final ChatModelRegistryService chatModelRegistryService = mock(ChatModelRegistryService.class);
    private final FinanceConversationGuard guard = new FinanceConversationGuard(
            chatMemory,
            new FinanceQuestionClassifier(),
            new ChatModelExecutionService(),
            chatModelRegistryService
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
    void shouldTreatFinanceFollowUpAsFinanceWhenModelUsesContext() {
        when(chatModelRegistryService.getInternalTaskChatModel(nullable(String.class))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(jsonResponse(
                """
                        {"financeRelated":true,"label":"finance","reason":"这是对前文股票分析的追问"}
                        """
        ));
        chatMemory.add("finance_guard_001", List.of(
                new UserMessage("请分析中国卫星的财务情况"),
                new AssistantMessage("可以，我先看财报和估值。")
        ));

        boolean financeRelated = guard.isFinanceRelatedQuestion("finance_guard_001", "那现在能买吗", null);

        Assertions.assertTrue(financeRelated);
    }

    @Test
    void shouldRejectTopicSwitchEvenIfHistoryWasFinance() {
        when(chatModelRegistryService.getInternalTaskChatModel(nullable(String.class))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(jsonResponse(
                """
                        {"financeRelated":false,"label":"non_finance","reason":"当前问题已经切换到编程话题"}
                        """
        ));
        chatMemory.add("finance_guard_002", List.of(
                new UserMessage("请分析宁德时代"),
                new AssistantMessage("我们先看盈利能力和估值。")
        ));

        boolean financeRelated = guard.isFinanceRelatedQuestion("finance_guard_002", "顺便帮我写个贪吃蛇小游戏", null);

        Assertions.assertFalse(financeRelated);
    }

    @Test
    void shouldFallbackToHeuristicContextWhenModelFails() {
        when(chatModelRegistryService.getInternalTaskChatModel(nullable(String.class))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("classifier model unavailable"));
        chatMemory.add("finance_guard_003", List.of(
                new UserMessage("分析一下贵州茅台"),
                new AssistantMessage("可以，我们先看护城河和估值。")
        ));

        boolean financeRelated = guard.isFinanceRelatedQuestion("finance_guard_003", "风险呢", null);

        Assertions.assertTrue(financeRelated);
    }

    private ChatResponse jsonResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
