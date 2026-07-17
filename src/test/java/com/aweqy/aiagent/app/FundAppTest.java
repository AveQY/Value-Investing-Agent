package com.aweqy.aiagent.app;

import com.aweqy.aiagent.AiAgentApplication;
import com.aweqy.aiagent.constant.FileConstant;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

@SpringBootTest(classes = AiAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "RUN_AI_INTEGRATION_TESTS", matches = "true")
class FundAppTest {

    private static final Path TOOL_TEST_FILE = Path.of(FileConstant.FILE_SAVE_DIR, "file", "fund-app-tool-test.txt");

    @Resource
    private FundApp fundApp;

    @Resource(name = "allTools")
    private ToolCallback[] allTools;

    @Resource
    private Environment environment;

    @DynamicPropertySource
    static void registerIntegrationOverrides(DynamicPropertyRegistry registry) {
        registerEnvOverride(registry, "AI_INTEGRATION_API_KEY", "spring.ai.openai.api-key");
        registerEnvOverride(registry, "AI_INTEGRATION_BASE_URL", "spring.ai.openai.base-url");
        registerEnvOverride(registry, "AI_INTEGRATION_MODEL", "spring.ai.openai.chat.options.model");
        String embeddingModel = System.getenv("AI_INTEGRATION_EMBEDDING_MODEL");
        String chatModel = System.getenv("AI_INTEGRATION_MODEL");
        if (StringUtils.hasText(embeddingModel)) {
            registry.add("spring.ai.openai.embedding.options.model", () -> embeddingModel);
        } else if (StringUtils.hasText(chatModel)) {
            registry.add("spring.ai.openai.embedding.options.model", () -> chatModel);
        }
        String vectorTable = System.getenv("AI_INTEGRATION_VECTOR_TABLE");
        registry.add("app.rag.vector-table-name",
                () -> StringUtils.hasText(vectorTable) ? vectorTable : "fund_app_test_vector_store");
    }

    @Test
    void testChat() {
        assumeOpenAiIsConfigured();

        String chatId = UUID.randomUUID().toString();

        String answer = callRealModel("你好，你能从价值投资角度介绍一下你擅长回答哪些财经问题吗？", chatId, "第1轮(real)");
        Assertions.assertTrue(StringUtils.hasText(answer), "真实模型第1轮没有返回内容");

        answer = callRealModel("我想知道比亚迪可以作为长期投资的目标吗", chatId, "第2轮(real)");
        Assertions.assertTrue(StringUtils.hasText(answer), "真实模型第2轮没有返回内容");

        answer = callRealModel("刚跟你说了什么，帮我回忆一下", chatId, "第3轮(real)");
        Assertions.assertTrue(StringUtils.hasText(answer), "真实模型第3轮没有返回内容");
    }

    @Test
    void doChatWithReport() {
        assumeOpenAiIsConfigured();

        String chatId = UUID.randomUUID().toString();
        FundApp.FundReport fundReport = callWithRetry(
                () -> fundApp.doChatWithReport("请基于比亚迪给我一份简洁的投资分析报告，只返回系统要求的结构化结果。", chatId),
                "结构化报告"
        );

        Assertions.assertNotNull(fundReport, "真实模型没有返回报告对象");
        Assertions.assertTrue(StringUtils.hasText(fundReport.title()), "真实模型返回的报告标题为空");
        Assertions.assertFalse(fundReport.suggestions().isEmpty(), "真实模型返回的建议列表为空");
        System.out.println("report title: " + fundReport.title());
        System.out.println("report suggestions: " + fundReport.suggestions());
    }

    @Test
    void doChatWithRag() {
        assumeOpenAiIsConfigured();

        String chatId = UUID.randomUUID().toString();
        String answer = callWithRetry(
                () -> fundApp.doChatWithRag("为什么我挂单的价格到了，却没有成交？", chatId),
                "RAG 检索回答"
        );

        printAnswer("RAG(real)", answer);
        Assertions.assertTrue(StringUtils.hasText(answer), "真实模型 RAG 返回内容为空");
        Assertions.assertTrue(answer.contains("成交") || answer.contains("价格") || answer.contains("排队"),
                "真实 RAG 返回内容看起来没有结合挂单成交问题: " + answer);
    }

    @Test
    void doChatWithTools() {
        assumeOpenAiIsConfigured();
        Assertions.assertTrue(allTools.length > 0, "真实工具列表为空");
        deleteToolTestFileIfExists();

        String chatId = UUID.randomUUID().toString();
        String answer = callWithRetry(
                () -> fundApp.doChatWithTools(
                        "请使用 writeFile 工具把“长期投资测试内容”写入 fund-app-tool-test.txt，然后告诉我写入结果。",
                        chatId
                ),
                "工具调用"
        );

        printAnswer("Tool(real)", answer);
        Assertions.assertTrue(StringUtils.hasText(answer), "真实模型工具调用返回内容为空");
        Assertions.assertTrue(
                Files.exists(TOOL_TEST_FILE) || looksLikeToolCallMarkup(answer),
                "真实模型既没有执行真实工具，也没有返回工具调用标记"
        );
        if (Files.exists(TOOL_TEST_FILE)) {
            Assertions.assertDoesNotThrow(() -> {
                String content = Files.readString(TOOL_TEST_FILE);
                Assertions.assertTrue(content.contains("长期投资测试内容"), "真实工具写入的文件内容不正确");
            });
        }
    }

    private void assumeOpenAiIsConfigured() {
        String apiKey = environment.getProperty("spring.ai.openai.api-key", "");
        String baseUrl = environment.getProperty("spring.ai.openai.base-url", "");
        Assumptions.assumeTrue(StringUtils.hasText(apiKey) && !isPlaceholder(apiKey),
                "RUN_AI_INTEGRATION_TESTS=true，但 spring.ai.openai.api-key 仍是占位值或未配置");
        Assumptions.assumeTrue(StringUtils.hasText(baseUrl),
                "RUN_AI_INTEGRATION_TESTS=true，但 spring.ai.openai.base-url 未配置");
        Assumptions.assumeTrue(!looksLikeVersionedBaseUrl(baseUrl),
                "spring.ai.openai.base-url 不应以 /v1 结尾。Spring AI 1.0.0-M6 会自行拼接 /v1/chat/completions，请改为服务根地址。当前值: " + baseUrl);
    }

    private static boolean isPlaceholder(String value) {
        return !StringUtils.hasText(value) || value.contains("your-api-key");
    }

    private String callRealModel(String message, String chatId, String round) {
        String answer = callWithRetry(() -> fundApp.doChat(message, chatId), round);
        printAnswer(round, answer);
        return answer;
    }

    private <T> T callWithRetry(Supplier<T> supplier, String operationName) {
        int maxAttempts = 3;
        long[] backoffMillis = {0L, 3000L, 8000L};
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                sleepQuietly(backoffMillis[attempt - 1]);
            }
            try {
                return supplier.get();
            } catch (Exception ex) {
                NonTransientAiException aiEx = findCause(ex, NonTransientAiException.class);
                if (aiEx == null) {
                    throw ex;
                }
                if (isAccessForbidden(aiEx)) {
                    throw new TestAbortedException(buildForbiddenMessage(operationName, ex, aiEx));
                }
                if (isMisconfiguredBaseUrl(aiEx)) {
                    throw new TestAbortedException(buildMisconfiguredBaseUrlMessage(aiEx));
                }
                if (!isRateLimited(aiEx)) {
                    if (ex instanceof BeanCreationException beanCreationException) {
                        throw beanCreationException;
                    }
                    throw ex;
                }
                if (attempt == maxAttempts) {
                    throw new TestAbortedException(operationName + " 被限流。 " + buildRateLimitMessage(aiEx));
                }
            }
        }
        throw new IllegalStateException("调用真实模型时发生未预期的重试流程错误");
    }

    private boolean isAccessForbidden(NonTransientAiException ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message)
                && (message.contains("403")
                || message.contains("ForbbidenUser")
                || message.contains("禁止访问"));
    }

    private boolean isRateLimited(NonTransientAiException ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message)
                && (message.contains("429")
                || message.toLowerCase().contains("rate limited")
                || message.contains("\"code\":\"1302\""));
    }

    private boolean isMisconfiguredBaseUrl(NonTransientAiException ex) {
        String message = ex.getMessage();
        String baseUrl = environment.getProperty("spring.ai.openai.base-url", "");
        return StringUtils.hasText(message)
                && message.contains("404")
                && (message.toLowerCase().contains("page not found") || looksLikeVersionedBaseUrl(baseUrl));
    }

    private String buildRateLimitMessage(NonTransientAiException ex) {
        String model = environment.getProperty("spring.ai.openai.chat.options.model", "unknown-model");
        return "真实模型联调被限流，当前模型为 " + model
                + "。可稍后重试，或设置 AI_INTEGRATION_MODEL 切换到更稳定的模型。原始错误: "
                + ex.getMessage();
    }

    private String buildForbiddenMessage(String operationName, Exception originalEx, NonTransientAiException ex) {
        String accessTarget = detectAccessTarget(originalEx);
        String model = resolveModelName(accessTarget);
        return operationName + " 当前无法继续，因为提供方拒绝了 " + accessTarget + " 访问。当前模型为 "
                + model + "。这通常表示当前账号或通道没有该模型权限，或者该 key 已被 provider 禁止访问。原始错误: " + ex.getMessage();
    }

    private String detectAccessTarget(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            for (StackTraceElement element : current.getStackTrace()) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                if ("org.springframework.ai.openai.api.OpenAiApi".equals(className)) {
                    if ("embeddings".equals(methodName)) {
                        return "embedding";
                    }
                    if ("chatCompletionEntity".equals(methodName) || "chatCompletionStream".equals(methodName)) {
                        return "chat";
                    }
                }
            }
            current = current.getCause();
        }
        return "AI 接口";
    }

    private String resolveModelName(String accessTarget) {
        if ("embedding".equals(accessTarget)) {
            return environment.getProperty("spring.ai.openai.embedding.options.model", "unknown-embedding-model");
        }
        if ("chat".equals(accessTarget)) {
            return environment.getProperty("spring.ai.openai.chat.options.model", "unknown-chat-model");
        }
        return environment.getProperty("spring.ai.openai.chat.options.model", "unknown-model");
    }

    private String buildMisconfiguredBaseUrlMessage(NonTransientAiException ex) {
        String baseUrl = environment.getProperty("spring.ai.openai.base-url", "");
        return "真实模型联调命中了错误的 OpenAI 接口地址。Spring AI 1.0.0-M6 会在 base-url 后追加 /v1/chat/completions，"
                + "所以这里应配置服务根地址而不是 /v1。当前值: " + baseUrl + "。原始错误: " + ex.getMessage();
    }

    private boolean looksLikeVersionedBaseUrl(String baseUrl) {
        return StringUtils.hasText(baseUrl) && baseUrl.matches(".*/v\\d+/?$");
    }

    private boolean looksLikeToolCallMarkup(String answer) {
        return StringUtils.hasText(answer)
                && (answer.contains("writeFile")
                || answer.contains("tool▁call")
                || answer.contains("tool_calls"));
    }

    private static void registerEnvOverride(DynamicPropertyRegistry registry, String envKey, String propertyKey) {
        String value = System.getenv(envKey);
        if (StringUtils.hasText(value)) {
            registry.add(propertyKey, () -> value);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TestAbortedException("等待限流恢复时被中断");
        }
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static void printAnswer(String round, String answer) {
        System.out.println(round + " answer: " + answer);
    }

    @AfterEach
    void cleanupGeneratedToolFile() {
        deleteToolTestFileIfExists();
    }

    private void deleteToolTestFileIfExists() {
        try {
            Files.deleteIfExists(TOOL_TEST_FILE);
        } catch (Exception ex) {
            System.out.println("cleanup tool file failed: " + ex.getMessage());
        }
    }
}
