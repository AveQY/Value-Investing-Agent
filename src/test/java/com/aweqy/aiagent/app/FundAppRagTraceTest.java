package com.aweqy.aiagent.app;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.aweqy.aiagent.service.ChatHistoryService;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.FinanceQuestionClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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

class FundAppRagTraceTest {

    private final Path memoryDir = Path.of("target", "test-chat-memory", "fund-app-rag-trace");
    private final FileBasedChatMemory chatMemory = new FileBasedChatMemory(memoryDir.toAbsolutePath().toString());
    private final ChatModel chatModel = mock(ChatModel.class);
    private final FundAppLocalDocumentRetriever localDocumentRetriever = mock(FundAppLocalDocumentRetriever.class);
    private final FundApp fundApp = new FundApp(
            chatModel,
            chatMemory,
            localDocumentRetriever,
            new FinanceQuestionClassifier(),
            new ChatMemoryMetadataService(chatMemory)
    );
    private final ChatHistoryService chatHistoryService = new ChatHistoryService(chatMemory);

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
    void shouldExposeRagTraceInHistoryAfterRagReply() {
        when(localDocumentRetriever.search(any(), any(Integer.class)))
                .thenReturn(List.of(new FundAppLocalDocumentRetriever.RetrievedDocument(
                        "价值投资-分析清单",
                        "ROE 需要结合现金流和利润质量一起看。",
                        88
                )));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("这是带引用的回答"))
                )));

        String answer = fundApp.doChatWithRag("分析贵州茅台", "rag_trace_001");

        Assertions.assertEquals("这是带引用的回答", answer);
        var history = chatHistoryService.getHistory("rag_trace_001");
        Assertions.assertEquals(2, history.messageCount());
        Assertions.assertEquals("分析贵州茅台", history.messages().get(0).content());
        Assertions.assertNotNull(history.messages().get(1).trace());
        Assertions.assertEquals("价值投资-分析清单", history.messages().get(1).trace().ragReferences().get(0).source());
    }
}
