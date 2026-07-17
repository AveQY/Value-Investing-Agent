package com.aweqy.aiagent.filter;

import com.aweqy.aiagent.app.FundApp;
import com.aweqy.aiagent.config.ChatMemoryConfig;
import com.aweqy.aiagent.config.OpenApiConfig;
import com.aweqy.aiagent.controller.FundAppController;
import com.aweqy.aiagent.exception.GlobalExceptionHandler;
import com.aweqy.aiagent.service.ChatHistoryService;
import com.aweqy.aiagent.service.ValueAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ApiKeyAuthFilterTest.TestApplication.class,
        properties = {
                "server.servlet.context-path=",
                "app.api.security.enabled=true",
                "app.api.security.key=test-api-key",
                "spring.mvc.pathmatch.matching-strategy=ant_path_matcher",
                "knife4j.enable=false"
        }
)
@AutoConfigureMockMvc
class ApiKeyAuthFilterTest {

    private static final Path MEMORY_DIR = Path.of("target", "test-chat-memory", "security");

    @MockBean
    private FundApp fundApp;

    @MockBean
    private ValueAnalysisService valueAnalysisService;

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.chat.memory.dir", () -> MEMORY_DIR.toAbsolutePath().toString());
    }

    @Test
    void shouldRejectRequestWhenApiKeyMissing() throws Exception {
        mockMvc.perform(post("/fund/chat")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_auth_001",
                                  "message": "测试鉴权"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("API Key 无效或缺失"));
    }

    @Test
    void shouldAllowRequestWhenApiKeyCorrect() throws Exception {
        org.mockito.Mockito.when(fundApp.doChat("测试鉴权", "chat_auth_001")).thenReturn("鉴权通过");

        mockMvc.perform(post("/fund/chat")
                        .header("X-API-KEY", "test-api-key")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "chatId": "chat_auth_001",
                                  "message": "测试鉴权"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("鉴权通过"));
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
            GlobalExceptionHandler.class,
            ApiKeyAuthFilter.class
    })
    static class TestApplication {
    }
}
