package com.aweqy.aiagent.service;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import com.aweqy.aiagent.exception.ChatHistoryNotFoundException;
import com.aweqy.aiagent.model.ChatHistoryResponse;
import com.aweqy.aiagent.model.ChatSessionCatalogResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

class ChatHistoryServiceTest {

    private final Path memoryDir = Path.of("target", "test-chat-memory", "service");
    private final FileBasedChatMemory chatMemory = new FileBasedChatMemory(memoryDir.toAbsolutePath().toString());
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
    void shouldReturnHistory() {
        chatMemory.add("service_chat_001", List.of(
                new UserMessage("用户提问"),
                new AssistantMessage("AI 回答")
        ));

        ChatHistoryResponse history = chatHistoryService.getHistory("service_chat_001");

        Assertions.assertEquals(2, history.messageCount());
        Assertions.assertEquals("USER", history.messages().get(0).role());
        Assertions.assertEquals("AI 回答", history.messages().get(1).content());
    }

    @Test
    void shouldDeleteHistory() {
        chatMemory.add("service_chat_002", List.of(new UserMessage("待删除")));

        chatHistoryService.deleteHistory("service_chat_002");

        Assertions.assertFalse(chatMemory.exists("service_chat_002"));
    }

    @Test
    void shouldThrowWhenHistoryMissing() {
        Assertions.assertThrows(ChatHistoryNotFoundException.class,
                () -> chatHistoryService.getHistory("service_missing"));
        Assertions.assertThrows(ChatHistoryNotFoundException.class,
                () -> chatHistoryService.deleteHistory("service_missing"));
    }

    @Test
    void shouldReturnReportMetadata() {
        chatMemory.add("service_chat_report_001", List.of(
                new AssistantMessage(
                        "宁德时代结构化投资报告\n\n- 关注估值",
                        Map.of(
                                ChatMemoryMetadataService.MESSAGE_KIND_KEY, ChatMemoryMetadataService.REPORT_KIND,
                                ChatMemoryMetadataService.REPORT_TITLE_KEY, "宁德时代结构化投资报告",
                                ChatMemoryMetadataService.REPORT_FILE_NAME_KEY, "宁德时代结构化投资报告.pdf",
                                ChatMemoryMetadataService.REPORT_DOWNLOAD_PATH_KEY, "/fund/reports/report-001.pdf/download"
                        )
                )
        ));

        ChatHistoryResponse history = chatHistoryService.getHistory("service_chat_report_001");

        Assertions.assertEquals("report", history.messages().get(0).kind());
        Assertions.assertEquals("宁德时代结构化投资报告", history.messages().get(0).title());
        Assertions.assertEquals("宁德时代结构化投资报告.pdf", history.messages().get(0).reportFileName());
        Assertions.assertEquals("/fund/reports/report-001.pdf/download", history.messages().get(0).reportDownloadPath());
    }

    @Test
    void shouldListSessionsDirectlyFromMemoryFiles() throws Exception {
        chatMemory.add("service_chat_list_001", List.of(
                new UserMessage(
                        "用户原始问题：\n请分析宁德时代",
                        List.of(),
                        Map.of(ChatMemoryMetadataService.DISPLAY_CONTENT_KEY, "请分析宁德时代")
                ),
                new AssistantMessage("收到")
        ));
        chatMemory.add("service_chat_list_002", List.of(
                new AssistantMessage(
                        "贵州茅台结构化投资报告",
                        Map.of(
                                ChatMemoryMetadataService.MESSAGE_KIND_KEY, ChatMemoryMetadataService.REPORT_KIND,
                                ChatMemoryMetadataService.REPORT_TITLE_KEY, "贵州茅台结构化投资报告"
                        )
                )
        ));

        Files.setLastModifiedTime(
                memoryDir.resolve("service_chat_list_001.kryo"),
                FileTime.from(Instant.parse("2026-04-22T10:15:30Z"))
        );
        Files.setLastModifiedTime(
                memoryDir.resolve("service_chat_list_002.kryo"),
                FileTime.from(Instant.parse("2026-04-22T12:15:30Z"))
        );

        ChatSessionCatalogResponse sessions = chatHistoryService.listSessions();

        Assertions.assertEquals(2, sessions.sessions().size());
        Assertions.assertEquals("service_chat_list_002", sessions.sessions().get(0).chatId());
        Assertions.assertEquals("贵州茅台结构化投资报告", sessions.sessions().get(0).preview());
        Assertions.assertEquals("service_chat_list_001", sessions.sessions().get(1).chatId());
        Assertions.assertEquals("请分析宁德时代", sessions.sessions().get(1).preview());
    }
}
