package com.aweqy.aiagent.service;

import com.aweqy.aiagent.advisor.MyLoggerAdvisor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public class FinanceConversationGuard {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int CONTEXT_MESSAGE_LIMIT = 8;
    private static final int MAX_MESSAGE_PREVIEW_LENGTH = 220;
    private static final String DOMAIN_CLASSIFIER_PROMPT = """
            你是一个严格的财经域分类器，只负责判断“当前用户最后一条消息”是不是财经与投资相关问题。

            判断时必须结合最近会话上下文，但分类对象永远是“当前用户最后一条消息”，不要把历史问题当成当前问题。

            判定规则：
            1. 股票、基金、指数、财报、公告、估值、资产配置、交易、行情、公司基本面、价值投资，都属于 finance。
            2. 如果当前消息是在追问前文财经话题，例如“继续”“展开讲讲”“那现在能买吗”“风险呢”“它贵吗”，也属于 finance。
            3. 如果当前消息明显切换到代码、旅游、美食、作文、翻译、图片、游戏、健身、景点等非财经话题，即使前文聊过财经，也属于 non_finance。
            4. 不要因为历史上下文是财经，就把明显跑题的问题误判成 finance。

            只允许输出一个 JSON 对象，不要输出解释、Markdown 或代码块：
            {"financeRelated":true,"label":"finance","reason":"一句话原因"}
            或
            {"financeRelated":false,"label":"non_finance","reason":"一句话原因"}
            """;

    private final ChatMemory chatMemory;
    private final FinanceQuestionClassifier financeQuestionClassifier;
    private final ChatModelExecutionService chatModelExecutionService;
    private final ChatModelRegistryService chatModelRegistryService;

    public FinanceConversationGuard(ChatMemory chatMemory,
                                    FinanceQuestionClassifier financeQuestionClassifier,
                                    ChatModelExecutionService chatModelExecutionService,
                                    ChatModelRegistryService chatModelRegistryService) {
        this.chatMemory = chatMemory;
        this.financeQuestionClassifier = financeQuestionClassifier;
        this.chatModelExecutionService = chatModelExecutionService;
        this.chatModelRegistryService = chatModelRegistryService;
    }

    public boolean isFinanceRelatedQuestion(String chatId, String message, String modelId) {
        FinanceDomainDecision decision = assess(chatId, message, modelId);
        log.info("finance domain decision={}, source={}, reason={}, chatId={}, message={}",
                decision.financeRelated(),
                decision.source(),
                decision.reason(),
                chatId,
                message);
        return decision.financeRelated();
    }

    FinanceDomainDecision assess(String chatId, String message, String modelId) {
        String trimmedMessage = StringUtils.trimWhitespace(message);
        if (!StringUtils.hasText(trimmedMessage)) {
            return new FinanceDomainDecision(false, "空消息不属于财经问题", "empty");
        }

        boolean currentFinance = financeQuestionClassifier.isFinanceRelatedQuestion(trimmedMessage);
        boolean clearlyNonFinance = financeQuestionClassifier.isClearlyNonFinanceQuestion(trimmedMessage);
        List<Message> recentMessages = readRecentMessages(chatId);
        boolean financeContext = financeQuestionClassifier.hasFinanceConversationContext(recentMessages);
        boolean financeFollowUp = financeQuestionClassifier.isFinanceFollowUpQuestion(trimmedMessage);

        FinanceDomainDecision modelDecision = assessByModel(chatId, trimmedMessage, recentMessages, modelId);
        if (modelDecision != null) {
            return modelDecision;
        }

        if (currentFinance) {
            return new FinanceDomainDecision(true, "当前问题本身命中财经语义", "heuristic-current");
        }
        if (clearlyNonFinance) {
            return new FinanceDomainDecision(false, "当前问题包含明显非财经话题", "heuristic-current");
        }
        if (financeFollowUp && financeContext) {
            return new FinanceDomainDecision(true, "当前问题是对前文财经话题的追问", "heuristic-context");
        }
        return new FinanceDomainDecision(false, "当前问题缺少财经语义，且上下文不足以支撑财经追问", "heuristic-fallback");
    }

    private FinanceDomainDecision assessByModel(String chatId,
                                                String message,
                                                List<Message> recentMessages,
                                                String modelId) {
        String context = buildConversationContext(chatId, recentMessages);
        try {
            String response = chatModelExecutionService.executeWithRetry(
                    "财经域分类",
                    () -> createInternalTaskClient(modelId)
                            .prompt()
                            .system(DOMAIN_CLASSIFIER_PROMPT)
                            .user("""
                                    最近会话上下文：
                                    %s

                                    当前用户最后一条消息：
                                    %s
                                    """.formatted(context, message))
                            .call()
                            .content(),
                    false
            );
            return parseDecision(response);
        } catch (RuntimeException ex) {
            log.warn("Finance domain model classification failed, fallback to heuristics. chatId={}", chatId);
            log.debug("Finance domain model classification failure for chatId={}", chatId, ex);
            return null;
        }
    }

    private FinanceDomainDecision parseDecision(String response) {
        if (!StringUtils.hasText(response)) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response);
            if (!root.isObject()) {
                return null;
            }
            boolean financeRelated = root.path("financeRelated").asBoolean(false);
            String label = root.path("label").asText("");
            String reason = firstNonBlank(root.path("reason").asText(""), label);
            if (!StringUtils.hasText(label) && !root.has("financeRelated")) {
                return null;
            }
            if ("finance".equalsIgnoreCase(label)) {
                financeRelated = true;
            } else if ("non_finance".equalsIgnoreCase(label)) {
                financeRelated = false;
            }
            return new FinanceDomainDecision(financeRelated, firstNonBlank(reason, "模型已完成财经域判断"), "model");
        } catch (Exception ex) {
            log.warn("Failed to parse finance domain classification response: {}", response);
            log.debug("Finance domain classification parsing failure", ex);
            return null;
        }
    }

    private List<Message> readRecentMessages(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return List.of();
        }
        try {
            List<Message> messages = chatMemory.get(chatId, CONTEXT_MESSAGE_LIMIT);
            return messages == null ? List.of() : messages;
        } catch (RuntimeException ex) {
            log.warn("Failed to read recent chat memory for finance domain guard. chatId={}", chatId);
            log.debug("Finance domain guard memory read failure for chatId={}", chatId, ex);
            return List.of();
        }
    }

    private String buildConversationContext(String chatId, List<Message> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return "无历史上下文。chatId=" + firstNonBlank(chatId, "unknown");
        }

        List<String> lines = new ArrayList<>();
        for (Message recentMessage : recentMessages) {
            if (recentMessage == null || !StringUtils.hasText(recentMessage.getText())) {
                continue;
            }
            String role = resolveRoleName(recentMessage.getMessageType());
            lines.add(role + "：" + abbreviate(recentMessage.getText()));
        }
        return lines.isEmpty() ? "无可用历史上下文。chatId=" + firstNonBlank(chatId, "unknown") : String.join("\n", lines);
    }

    private String resolveRoleName(MessageType messageType) {
        if (messageType == null) {
            return "未知角色";
        }
        if (messageType == MessageType.USER) {
            return "用户";
        }
        if (messageType == MessageType.ASSISTANT) {
            return "助手";
        }
        if (messageType == MessageType.SYSTEM) {
            return "系统";
        }
        return messageType.name().toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String value) {
        String normalized = String.valueOf(value).replace("\r", "").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_MESSAGE_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_MESSAGE_PREVIEW_LENGTH) + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private ChatClient createInternalTaskClient(String modelId) {
        return ChatClient.builder(chatModelRegistryService.getInternalTaskChatModel(modelId))
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    record FinanceDomainDecision(boolean financeRelated, String reason, String source) {
    }
}
