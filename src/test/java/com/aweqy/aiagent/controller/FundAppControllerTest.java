package com.aweqy.aiagent.controller;

import com.aweqy.aiagent.app.FundApp;
import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import com.aweqy.aiagent.config.ChatMemoryConfig;
import com.aweqy.aiagent.config.OpenApiConfig;
import com.aweqy.aiagent.exception.GlobalExceptionHandler;
import com.aweqy.aiagent.model.ChatSessionLockResponse;
import com.aweqy.aiagent.filter.ApiKeyAuthFilter;
import com.aweqy.aiagent.model.ChatModelSummary;
import com.aweqy.aiagent.service.ChatHistoryService;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.ChatModelRegistryService;
import com.aweqy.aiagent.service.ReportDocumentService;
import com.aweqy.aiagent.service.SessionLockService;
import com.aweqy.aiagent.service.ValueAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.nullable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = FundAppControllerTest.TestApplication.class,
        properties = {
                "server.servlet.context-path=",
                "springdoc.api-docs.path=/v3/api-docs",
                "spring.mvc.pathmatch.matching-strategy=ant_path_matcher",
                "knife4j.enable=false"
        }
)
@AutoConfigureMockMvc
class FundAppControllerTest {

    private static final Path MEMORY_DIR = Path.of("target", "test-chat-memory", "controller");

    @MockBean
    private FundApp fundApp;

    @MockBean
    private ValueAnalysisService valueAnalysisService;

    @MockBean
    private ChatModelRegistryService chatModelRegistryService;

    @MockBean
    private ReportDocumentService reportDocumentService;

    @jakarta.annotation.Resource
    private SessionLockService sessionLockService;

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @jakarta.annotation.Resource
    private org.springframework.ai.chat.memory.ChatMemory chatMemory;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.chat.memory.dir", () -> MEMORY_DIR.toAbsolutePath().toString());
    }

    @BeforeEach
    void setUpModelRegistry() {
        ChatModelSummary defaultModel = new ChatModelSummary(
                "mock-model",
                "Mock Model",
                "openai-compatible",
                "https://example.com",
                "mock-provider-model",
                true,
                false,
                true,
                "mock...key"
        );

        when(chatModelRegistryService.getModelSummary(nullable(String.class))).thenReturn(defaultModel);
        when(chatModelRegistryService.resolveModelId(nullable(String.class))).thenReturn(defaultModel.id());
    }

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(MEMORY_DIR)) {
            try (var paths = Files.walk(MEMORY_DIR)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
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
    void shouldChatSuccessfully() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_001");
        when(fundApp.doChat(eq("分析一下茅台的长期价值"), eq("chat_001"), eq("buffett_framework"), any()))
                .thenReturn("茅台具有较强品牌护城河");

        mockMvc.perform(post("/fund/chat")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_001",
                                  "message": "分析一下茅台的长期价值",
                                  "advisorStyle": "buffett_framework"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("chat_001"))
                .andExpect(jsonPath("$.mode").value("CHAT"))
                .andExpect(jsonPath("$.advisorStyle").value("default"))
                .andExpect(jsonPath("$.content").value("茅台具有较强品牌护城河"));

        verify(fundApp).doChat(eq("分析一下茅台的长期价值"), eq("chat_001"), eq("buffett_framework"), any());
    }

    @Test
    void shouldGenerateReportSuccessfully() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_002");
        when(fundApp.doChatWithReport(eq("请生成宁德时代报告"), eq("chat_002"), eq("李雷"), eq("buffett_framework"), any(), any(), eq("value")))
                .thenReturn(new FundApp.FundReport(
                        "李雷的数据报告",
                        List.of("关注估值", "分批建仓"),
                        "李雷的数据报告.pdf",
                        "/fund/reports/report-chat_002.pdf/download"
                ));

        mockMvc.perform(post("/fund/chat/report")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_002",
                                  "message": "请生成宁德时代报告",
                                  "userName": "李雷",
                                  "advisorStyle": "buffett_framework",
                                  "mode": "value"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("chat_002"))
                .andExpect(jsonPath("$.title").value("李雷的数据报告"))
                .andExpect(jsonPath("$.suggestions[0]").value("关注估值"))
                .andExpect(jsonPath("$.reportFileName").value("李雷的数据报告.pdf"))
                .andExpect(jsonPath("$.reportDownloadPath").value("/fund/reports/report-chat_002.pdf/download"));

        verify(fundApp).doChatWithReport(eq("请生成宁德时代报告"), eq("chat_002"), eq("李雷"), eq("buffett_framework"), any(), any(), eq("value"));
    }

    @Test
    void shouldChatWithRagSuccessfully() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_003");
        when(fundApp.doChatWithRag(eq("为什么挂单没成交"), eq("chat_003"), eq("buffett_framework"), any()))
                .thenReturn("可能因为排队顺序和价格优先");

        mockMvc.perform(post("/fund/chat/rag")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_003",
                                  "message": "为什么挂单没成交",
                                  "advisorStyle": "buffett_framework"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RAG"))
                .andExpect(jsonPath("$.advisorStyle").value("default"))
                .andExpect(jsonPath("$.content").value("可能因为排队顺序和价格优先"));
    }

    @Test
    void shouldChatWithToolsSuccessfully() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_004");
        when(fundApp.doChatWithTools(eq("帮我生成报告文件"), eq("chat_004"), eq("buffett_framework"), any()))
                .thenReturn("已调用工具并生成文件");

        mockMvc.perform(post("/fund/chat/tools")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_004",
                                  "message": "帮我生成报告文件",
                                  "advisorStyle": "buffett_framework"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TOOLS"))
                .andExpect(jsonPath("$.advisorStyle").value("buffett_framework"))
                .andExpect(jsonPath("$.content").value("已调用工具并生成文件"));
    }

    @Test
    void shouldChatWithValueAnalysisSuccessfully() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_005");
        when(valueAnalysisService.analyze(eq("请按价值投资框架分析贵州茅台"), eq("chat_005"), eq("buffett_persona"), any()))
                .thenReturn("## 商业模式\n高端白酒具备强品牌心智。");

        mockMvc.perform(post("/fund/chat/value")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_005",
                                  "message": "请按价值投资框架分析贵州茅台",
                                  "advisorStyle": "buffett_persona"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("chat_005"))
                .andExpect(jsonPath("$.mode").value("VALUE"))
                .andExpect(jsonPath("$.advisorStyle").value("buffett_persona"))
                .andExpect(jsonPath("$.content").value("## 商业模式\n高端白酒具备强品牌心智。"));

        verify(valueAnalysisService).analyze(eq("请按价值投资框架分析贵州茅台"), eq("chat_005"), eq("buffett_persona"), any());
    }

    @Test
    void shouldReturnRuntimeConfigSuccessfully() throws Exception {
        mockMvc.perform(get("/fund/runtime-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolsMode.maxRounds").value(20))
                .andExpect(jsonPath("$.toolsMode.maxCallsPerRound").value(0))
                .andExpect(jsonPath("$.toolsMode.maxTotalCalls").value(200))
                .andExpect(jsonPath("$.valueAnalysis.maxRounds").value(20))
                .andExpect(jsonPath("$.valueAnalysis.maxCallsPerRound").value(0))
                .andExpect(jsonPath("$.valueAnalysis.maxTotalCalls").value(200));
    }

    @Test
    void shouldReturnHistorySuccessfully() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_hist_001");
        FileBasedChatMemory fileBasedChatMemory = (FileBasedChatMemory) chatMemory;
        fileBasedChatMemory.add("chat_hist_001", List.of(
                new UserMessage("第一轮问题"),
                new AssistantMessage("第一轮回答")
        ));

        mockMvc.perform(get("/fund/history/chat_hist_001")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("chat_hist_001"))
                .andExpect(jsonPath("$.messageCount").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[1].content").value("第一轮回答"));
    }

    @Test
    void shouldReturnHistorySessionsSuccessfully() throws Exception {
        FileBasedChatMemory fileBasedChatMemory = (FileBasedChatMemory) chatMemory;
        fileBasedChatMemory.add("chat_hist_list_001", List.of(
                new UserMessage(
                        "用户原始问题：\n请分析茅台",
                        List.of(),
                        Map.of(ChatMemoryMetadataService.DISPLAY_CONTENT_KEY, "请分析茅台")
                ),
                new AssistantMessage("第一轮回答")
        ));
        fileBasedChatMemory.add("chat_hist_list_002", List.of(
                new AssistantMessage(
                        "宁德时代结构化投资报告",
                        Map.of(
                                ChatMemoryMetadataService.MESSAGE_KIND_KEY, ChatMemoryMetadataService.REPORT_KIND,
                                ChatMemoryMetadataService.REPORT_TITLE_KEY, "宁德时代结构化投资报告"
                        )
                )
        ));

        Files.setLastModifiedTime(
                MEMORY_DIR.resolve("chat_hist_list_001.kryo"),
                FileTime.from(Instant.parse("2026-04-22T09:15:30Z"))
        );
        Files.setLastModifiedTime(
                MEMORY_DIR.resolve("chat_hist_list_002.kryo"),
                FileTime.from(Instant.parse("2026-04-22T11:15:30Z"))
        );

        mockMvc.perform(get("/fund/history/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].chatId").value("chat_hist_list_002"))
                .andExpect(jsonPath("$.sessions[0].preview").value("宁德时代结构化投资报告"))
                .andExpect(jsonPath("$.sessions[1].chatId").value("chat_hist_list_001"))
                .andExpect(jsonPath("$.sessions[1].preview").value("请分析茅台"));
    }

    @Test
    void shouldReturnTraceAndDisplayContentFromHistoryMetadata() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_hist_trace_001");
        FileBasedChatMemory fileBasedChatMemory = (FileBasedChatMemory) chatMemory;
        fileBasedChatMemory.add("chat_hist_trace_001", List.of(
                new UserMessage(
                        "用户原始问题：\n请分析茅台",
                        List.of(),
                        Map.of("displayContent", "请分析茅台")
                ),
                new AssistantMessage(
                        "这是回答",
                        Map.of(
                                "trace",
                                Map.of(
                                        "toolCalls", List.of(
                                                Map.of(
                                                        "title", "实时检索",
                                                        "toolName", "Search for information",
                                                        "arguments", "{\"query\":\"贵州茅台 最新财报\"}",
                                                        "outputPreview", "检索到最新财报摘要"
                                                )
                                        ),
                                        "ragReferences", List.of(
                                                Map.of(
                                                        "source", "价值投资-分析清单",
                                                        "snippet", "先看扣非 ROE，再看现金收入比率。"
                                                )
                                        ),
                                        "empty", false
                                )
                        )
                )
        ));

        mockMvc.perform(get("/fund/history/chat_hist_trace_001")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("请分析茅台"))
                .andExpect(jsonPath("$.messages[1].trace.toolCalls[0].toolName").value("Search for information"))
                .andExpect(jsonPath("$.messages[1].trace.ragReferences[0].source").value("价值投资-分析清单"));
    }

    @Test
    void shouldReturnReportMetadataFromHistory() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_hist_report_001");
        FileBasedChatMemory fileBasedChatMemory = (FileBasedChatMemory) chatMemory;
        fileBasedChatMemory.add("chat_hist_report_001", List.of(
                new AssistantMessage(
                        "宁德时代结构化投资报告\n\n- 关注估值",
                        Map.of(
                                ChatMemoryMetadataService.MESSAGE_KIND_KEY, ChatMemoryMetadataService.REPORT_KIND,
                                ChatMemoryMetadataService.REPORT_TITLE_KEY, "宁德时代结构化投资报告",
                                ChatMemoryMetadataService.REPORT_FILE_NAME_KEY, "宁德时代结构化投资报告.pdf",
                                ChatMemoryMetadataService.REPORT_DOWNLOAD_PATH_KEY, "/fund/reports/report-chat_hist_report_001.pdf/download"
                        )
                )
        ));

        mockMvc.perform(get("/fund/history/chat_hist_report_001")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].kind").value("report"))
                .andExpect(jsonPath("$.messages[0].title").value("宁德时代结构化投资报告"))
                .andExpect(jsonPath("$.messages[0].reportFileName").value("宁德时代结构化投资报告.pdf"))
                .andExpect(jsonPath("$.messages[0].reportDownloadPath").value("/fund/reports/report-chat_hist_report_001.pdf/download"));
    }

    @Test
    void shouldDeleteHistorySuccessfully() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_hist_002");
        FileBasedChatMemory fileBasedChatMemory = (FileBasedChatMemory) chatMemory;
        fileBasedChatMemory.add("chat_hist_002", List.of(new UserMessage("待删除记录")));

        mockMvc.perform(delete("/fund/history/chat_hist_002")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("聊天历史已删除"));

        Assertions.assertFalse(fileBasedChatMemory.exists("chat_hist_002"));
    }

    @Test
    void shouldAcquireSessionLockSuccessfully() throws Exception {
        mockMvc.perform(post("/fund/session-lock/chat_lock_001/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": "client_alpha"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("chat_lock_001"))
                .andExpect(jsonPath("$.clientId").value("client_alpha"))
                .andExpect(jsonPath("$.leaseToken").isNotEmpty())
                .andExpect(jsonPath("$.leaseTtlSeconds").value(30));
    }

    @Test
    void shouldBlockSecondClientWhenSessionAlreadyLocked() throws Exception {
        acquireLock("chat_lock_002");

        mockMvc.perform(post("/fund/session-lock/chat_lock_002/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": "client_beta"
                                }
                                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value("该会话正在被其他客户端使用，请稍后再试或新建对话。"))
                .andExpect(jsonPath("$.details.reason").value("occupied"));
    }

    @Test
    void shouldHeartbeatSuccessfullyForLockOwner() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_lock_003");

        mockMvc.perform(post("/fund/session-lock/chat_lock_003/heartbeat")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("chat_lock_003"))
                .andExpect(jsonPath("$.clientId").value(lock.clientId()))
                .andExpect(jsonPath("$.leaseToken").value(lock.leaseToken()));
    }

    @Test
    void shouldRejectProtectedEndpointWhenLeaseMissing() throws Exception {
        mockMvc.perform(get("/fund/history/chat_lock_required"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("lease_required"));
    }

    @Test
    void shouldReturnBadRequestWhenChatRequestInvalid() throws Exception {
        mockMvc.perform(post("/fund/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat 001",
                                  "message": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.details.message").value("message 不能为空"))
                .andExpect(jsonPath("$.details.chatId").value("chatId 只能包含字母、数字、下划线和短横线"));
    }

    @Test
    void shouldReturnNotFoundWhenHistoryMissing() throws Exception {
        ChatSessionLockResponse lock = acquireLock("missing_chat");

        mockMvc.perform(get("/fund/history/missing_chat")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("未找到 chatId=missing_chat 对应的聊天历史"));
    }

    @Test
    void shouldReturnInternalServerErrorWhenFundAppFails() throws Exception {
        ChatSessionLockResponse lock = acquireLock("chat_500");
        when(fundApp.doChat(anyString(), anyString(), nullable(String.class), nullable(String.class)))
                .thenThrow(new IllegalStateException("模型服务暂不可用"));

        mockMvc.perform(post("/fund/chat")
                        .header(SessionLockService.CLIENT_ID_HEADER, lock.clientId())
                        .header(SessionLockService.LEASE_TOKEN_HEADER, lock.leaseToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_500",
                                  "message": "测试异常"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("模型服务暂不可用"));
    }

    @Test
    void shouldGenerateOpenApiDocs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/fund/chat']").exists())
                .andExpect(jsonPath("$.paths['/fund/history/{chatId}']").exists());
    }

    private ChatSessionLockResponse acquireLock(String chatId) {
        return sessionLockService.acquire(chatId, "client_alpha");
    }

    @SpringBootApplication(exclude = {
            DataSourceAutoConfiguration.class,
            PgVectorStoreAutoConfiguration.class
    })
    @org.springframework.context.annotation.Import({
            ChatMemoryConfig.class,
            OpenApiConfig.class,
            FundAppController.class,
            ChatHistoryService.class,
            SessionLockService.class,
            GlobalExceptionHandler.class,
            ApiKeyAuthFilter.class
    })
    static class TestApplication {
    }
}
