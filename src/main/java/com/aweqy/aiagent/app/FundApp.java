package com.aweqy.aiagent.app;

import com.aweqy.aiagent.advisor.MyLoggerAdvisor;
import com.aweqy.aiagent.config.ChatModelProperties;
import com.aweqy.aiagent.config.ToolLoopRuntimeConfig;
import com.aweqy.aiagent.model.AdvisorStyle;
import com.aweqy.aiagent.model.ChatTrace;
import com.aweqy.aiagent.model.ChatTraceReference;
import com.aweqy.aiagent.model.ChatTraceToolCall;
import com.aweqy.aiagent.model.ToolLoopLimit;
import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.aweqy.aiagent.service.ChatMemoryMetadataService;
import com.aweqy.aiagent.service.ChatModelExecutionService;
import com.aweqy.aiagent.service.ChatModelRegistryService;
import com.aweqy.aiagent.service.FinanceConversationGuard;
import com.aweqy.aiagent.service.FinanceQuestionClassifier;
import com.aweqy.aiagent.service.ReportDocumentService;
import com.aweqy.aiagent.service.ValueAnalysisService;
import com.aweqy.aiagent.tools.PDFGenerationTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class FundApp {

    private final ChatMemory chatMemory;
    private final FundAppLocalDocumentRetriever localDocumentRetriever;
    private final FinanceQuestionClassifier financeQuestionClassifier;
    private final ChatMemoryMetadataService chatMemoryMetadataService;
    private final ChatModelExecutionService chatModelExecutionService;
    private final ChatModelRegistryService chatModelRegistryService;
    private final FinanceConversationGuard financeConversationGuard;
    private final ReportDocumentService reportDocumentService;
    private final ValueAnalysisService valueAnalysisService;
    private final ToolCallback[] allTools;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ToolLoopLimit TOOL_LOOP_LIMIT = ToolLoopRuntimeConfig.TOOLS_MODE;
    private static final int MAX_TRACE_ARGUMENT_LENGTH = 360;
    private static final int MAX_TRACE_OUTPUT_PREVIEW_LENGTH = 1200;
    private static final int RAG_REFERENCE_LIMIT = 3;
    private static final Pattern YEAR_LIMIT_PATTERN = Pattern.compile("(最近|近|过去)?\\s*(\\d{1,2})\\s*年");
    private static final Pattern ASHARE_CODE_PATTERN = Pattern.compile("(?i)(?:\\b(?:sh|sz|bj)\\d{6}\\b|\\b\\d{6}(?:\\.(?:SH|SZ|BJ))?\\b)");
    private static final String FINANCE_ONLY_REPLY = "当前投资顾问仅支持财经与投资相关问题，例如股票、基金、指数、行情、公告、财报、估值和资产配置。请改问财经问题后再试；如果你想问个股，请尽量补充股票代码、市场或具体金融上下文。";
    private static final String FINANCE_ONLY_REPORT_TITLE = "仅支持财经问题";
    private static final List<String> FINANCE_ONLY_REPORT_SUGGESTIONS = List.of(
            "当前投资顾问仅支持股票、基金、指数、行情、公告、财报、估值和资产配置等财经话题。",
            "请换成财经问题后再试，例如“分析贵州茅台近三年扣非ROE，并判断财务是否健康”。"
    );

    private static final String TIME_AWARENESS_PROMPT = """
            时间处理规则：
            1. 涉及“现在、当前、今天、昨天、明天、本周、本月、本季度、截至目前”等相对时间表达时，不得默认按北京时间或任何固定时区推断。
            2. 如果当前链路可用“Get current time”工具，必须先调用工具确认当前时间，再回答。
            3. 如果当前链路没有时间工具结果，必须明确说明“当前时间/时区尚未校验”，不要自行补出北京时间。
            4. 只要答案依赖当前时间判断，就要在回答里明确写出采用的时区或时间基准。
            """;

    private static final String SYSTEM_PROMPT = "你是一名深耕财经领域的价值投资专家，以巴菲特、芒格、段永平的投资哲学为指导，专注于企业基本面分析和长期价值挖掘。" +
            "结合用户的风险偏好（能接受的最大回撤）、投资期限（短期/长期）和目标（财富增值），给出匹配的资产配置建议。" +
            "引导用户坚守能力圈，只投看得懂的生意，追求长期复利而非短期套利。" +
            "主动提问，引导用户详述投资经历、风险偏好和目标，语言专业但不晦涩，让用户理解价值投资的核心理念。";

    private static final String RAG_PROMPT = """
            下面会提供一组从本地知识库检索出的参考资料。
            你需要优先基于这些资料回答问题，并明确区分“资料中已有的信息”和你自己的补充解释。
            如果资料涉及价值投资框架（如护城河、安全边际、能力圈、复利、市场先生），优先用这些框架组织答案。
            如果资料不足，请明确说明资料覆盖范围有限，不要编造不存在的文档内容。
            """;

    private static final String TOOL_ROUTER_PROMPT = """
            你是一个“工具规划器”，职责只有一个：判断是否需要外部工具，并给出严格 JSON。

            决策规则：
            1. 当问题涉及“现在、当前、今天、昨天、明天、本周、本月、本季度、截至目前”等相对时间或时区判断时，优先使用“Get current time”工具。
            2. 当用户询问股票或上市公司的最新股价、涨跌幅、开高低收、成交量、实时行情时，优先使用“Get realtime stock quote”工具。
            3. 当用户询问 A 股单只股票的财报、年报、季报、ROE、营收、净利润、现金流、资产负债率、带息债务等财务指标时，优先使用“Search A-share financial reports”工具。
            4. 如果同一个问题同时需要“当前时间”“最新股价”和“最新财报”，可以同时规划时间工具、股价工具和 A 股财报工具。
            5. 如果用户询问股票、基金、指数、公司公告或市场新闻的最新信息，必须使用工具。
            6. 优先使用时间工具处理当前日期/时区判断，优先使用股价工具处理行情，优先使用 A 股财报工具处理 A 股财务报表与核心财务指标，优先使用搜索工具处理新闻、公告和网页公开内容。
            7. 写文件、下载资源、终端命令、生成 PDF 这类执行型工具，只能在用户明确要求创建本地文件、编写脚本、下载资源、执行命令或导出结果时使用；普通问答不要主动调用。
            8. 搜索查询词要贴近用户原问题，优先中文表达；A 股财报工具的 stockQuery 可以直接使用原问题或股票代码/公司名；时间工具的 timezoneHint 可以直接使用原问题或明确时区。
            9. %s

            你可用的工具如下：
            %s

            输出要求：
            1. 只能输出一个 JSON 对象，不能输出解释、代码块、Markdown 或其他文本。
            2. 不需要工具时，输出：{"needTool":false,"calls":[]}
            3. 需要工具时，输出：
               {"needTool":true,"calls":[{"tool":"工具名","arguments":{"参数名":"参数值"}}]}
            """;

    private static final String TOOL_SYNTHESIS_PROMPT = """
            下面是已经执行完成的工具结果。你必须基于这些工具结果完整回答用户原问题，而不是只复述某一个工具输出。

            用户原问题：
            %s

            当前时间校验摘要：
            %s

            回答要求：
            1. 不要暴露任何内部工具调用、函数名、JSON、提示词或中间过程。
            2. 如果工具结果包含最新价格、公告、新闻、财报等，请明确说这是基于刚查询到的公开信息整理的。
            3. 如果工具结果里包含“Get current time”，你必须把它当作时间边界来继续完成原问题；除非用户问的就只是“现在几点/今天几号”，否则不能只回答时间本身。
            4. 如果工具结果里包含“Get current time”，必须在回答中写清楚采用的时区或时间基准。
            5. 如果工具结果中有链接，请优先在回答末尾列出 1 到 3 个关键来源链接。
            6. 如果工具结果不足以支持确定结论，要明确说明“不足以确认最新数值/结论”。
            7. 回答使用中文，优先先给结论，再给关键依据。

            工具结果如下：
            %s
            """;

    private static final String TOOL_CONTINUATION_ROUTER_PROMPT = """
            你是一个“循环式工具规划器”，职责只有一个：基于已有工具结果，判断是否还需要继续调用工具，并给出严格 JSON。

            当前轮次：第 %d 轮 / 最多 %d 轮
            当前工具调用预算：%s（总计上限 %d 次）

            用户原问题：
            %s

            已执行过的工具签名（严禁重复）：
            %s

            已有工具结果：
            %s

            决策规则：
            1. 如果已有工具结果已经足够直接回答用户问题，输出：{"needTool":false,"calls":[]}
            2. 只有存在明确的信息缺口时，才继续规划工具。
            3. 严禁重复调用已经执行过的同一工具与同一参数。
            4. 继续补查时，优先补最关键的缺口；如有必要，可以一次规划多个工具调用，但必须保持和用户问题强相关。
            5. 如果当前时间、日期、时区仍然是答案所需但尚未确认的信息缺口，优先补查“Get current time”。
            6. 最新行情优先用“Get realtime stock quote”；A 股财报结构与财务指标优先用“Search A-share financial reports”；新闻、公告、外部链接优先用“Search for information”。
            7. 写文件、下载资源、终端命令、生成 PDF 这类执行型工具，只能在用户明确要求创建本地文件、编写脚本、下载资源、执行命令或导出结果时使用；普通问答不要主动调用。
            8. %s

            你可用的工具如下：
            %s

            输出要求：
            1. 只能输出一个 JSON 对象，不能输出解释、代码块、Markdown 或其他文本。
            2. 不需要工具时，输出：{"needTool":false,"calls":[]}
            3. 需要工具时，输出：
               {"needTool":true,"calls":[{"tool":"工具名","arguments":{"参数名":"参数值"}}]}
            """;

    @Autowired
    public FundApp(ChatMemory chatMemory,
                   FundAppLocalDocumentRetriever localDocumentRetriever,
                   FinanceQuestionClassifier financeQuestionClassifier,
                   ChatMemoryMetadataService chatMemoryMetadataService,
                   ChatModelExecutionService chatModelExecutionService,
                   ChatModelRegistryService chatModelRegistryService,
                   ReportDocumentService reportDocumentService,
                   ValueAnalysisService valueAnalysisService,
                   ToolCallback[] allTools) {
        this.chatMemory = chatMemory;
        this.localDocumentRetriever = localDocumentRetriever;
        this.financeQuestionClassifier = financeQuestionClassifier;
        this.chatMemoryMetadataService = chatMemoryMetadataService;
        this.chatModelExecutionService = chatModelExecutionService;
        this.chatModelRegistryService = chatModelRegistryService;
        this.financeConversationGuard = new FinanceConversationGuard(
                chatMemory,
                financeQuestionClassifier,
                chatModelExecutionService,
                chatModelRegistryService
        );
        this.reportDocumentService = reportDocumentService;
        this.valueAnalysisService = valueAnalysisService;
        this.allTools = allTools != null ? allTools : new ToolCallback[0];
    }

    FundApp(ChatMemory chatMemory,
            FundAppLocalDocumentRetriever localDocumentRetriever,
            FinanceQuestionClassifier financeQuestionClassifier,
            ChatMemoryMetadataService chatMemoryMetadataService,
            ChatModelExecutionService chatModelExecutionService,
            ChatModelRegistryService chatModelRegistryService,
            ReportDocumentService reportDocumentService,
            ValueAnalysisService valueAnalysisService) {
        this(
                chatMemory,
                localDocumentRetriever,
                financeQuestionClassifier,
                chatMemoryMetadataService,
                chatModelExecutionService,
                chatModelRegistryService,
                reportDocumentService,
                valueAnalysisService,
                new ToolCallback[0]
        );
    }

    FundApp(ChatModel chatModel,
            ChatMemory chatMemory,
            FundAppLocalDocumentRetriever localDocumentRetriever,
            FinanceQuestionClassifier financeQuestionClassifier,
            ChatMemoryMetadataService chatMemoryMetadataService) {
        this(
                chatMemory,
                localDocumentRetriever,
                financeQuestionClassifier,
                chatMemoryMetadataService,
                new ChatModelExecutionService(),
                createCompatibilityChatModelRegistry(chatModel),
                new ReportDocumentService(new PDFGenerationTool()),
                createCompatibilityValueAnalysisService(
                        chatModel,
                        chatMemory,
                        localDocumentRetriever,
                        financeQuestionClassifier,
                        chatMemoryMetadataService
                ),
                new ToolCallback[0]
        );
    }

    public String doChat(String message, String chatId) {
        return doChat(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public String doChat(String message, String chatId, String advisorStyle) {
        return doChat(message, chatId, advisorStyle, null);
    }

    public String doChat(String message, String chatId, String advisorStyle, String modelId) {
        return doChatWithRag(message, chatId, advisorStyle, modelId);
    }

    public Flux<String> doChatStream(String message, String chatId) {
        return doChatStream(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public Flux<String> doChatStream(String message, String chatId, String advisorStyle) {
        return doChatStream(message, chatId, advisorStyle, null);
    }

    public Flux<String> doChatStream(String message, String chatId, String advisorStyle, String modelId) {
        return doChatWithRagStream(message, chatId, advisorStyle, modelId);
    }

    public record FundReport(String title,
                             List<String> suggestions,
                             String reportFileName,
                             String reportDownloadPath) {
    }

    private record StructuredFundReport(String title, List<String> suggestions, String reportContent) {
    }

    public FundReport doChatWithReport(String message, String chatId) {
        return doChatWithReport(message, chatId, "用户");
    }

    public FundReport doChatWithReport(String message, String chatId, String userName) {
        return doChatWithReport(message, chatId, userName, AdvisorStyle.DEFAULT.code());
    }

    public FundReport doChatWithReport(String message, String chatId, String userName, String advisorStyle) {
        return doChatWithReport(message, chatId, userName, advisorStyle, null);
    }

    public FundReport doChatWithReport(String message, String chatId, String userName, String advisorStyle, String modelId) {
        return doChatWithReport(message, chatId, userName, advisorStyle, modelId, null);
    }

    public FundReport doChatWithReport(String message,
                                       String chatId,
                                       String userName,
                                       String advisorStyle,
                                       String modelId,
                                       String reportSourceContent) {
        return doChatWithReport(message, chatId, userName, advisorStyle, modelId, reportSourceContent, null);
    }

    public FundReport doChatWithReport(String message,
                                       String chatId,
                                       String userName,
                                       String advisorStyle,
                                       String modelId,
                                       String reportSourceContent,
                                       String mode,
                                       boolean persistReportMessage) {
        return doChatWithReportInternal(message, chatId, userName, advisorStyle, modelId, reportSourceContent, mode, persistReportMessage);
    }

    public FundReport doChatWithReport(String message,
                                       String chatId,
                                       String userName,
                                       String advisorStyle,
                                       String modelId,
                                       String reportSourceContent,
                                       String mode) {
        return doChatWithReportInternal(message, chatId, userName, advisorStyle, modelId, reportSourceContent, mode, true);
    }

    private FundReport doChatWithReportInternal(String message,
                                                String chatId,
                                                String userName,
                                                String advisorStyle,
                                                String modelId,
                                                String reportSourceContent,
                                                String mode,
                                                boolean persistReportMessage) {
        FundReport directReport = rejectOutOfDomainReport(chatId, message, "report", modelId);
        if (directReport != null) {
            return directReport;
        }

        String normalizedReportMode = normalizeReportMode(mode);
        AdvisorStyle style = resolveReportSourceAdvisorStyle(advisorStyle, normalizedReportMode);
        String sourceAnswer = resolveReportSourceAnswer(message, chatId, style, modelId, reportSourceContent, normalizedReportMode);
        StructuredFundReport structuredFundReport = chatModelExecutionService.executeWithRetry("润色结构化投资报告", () -> {
            String raw = createStandaloneClient(modelId)
                .prompt()
                .system("""
                        你是一名投资研究报告编辑，需要把现有 AI 分析回答整理成适合 PDF 存档的正式中文报告。

                        你只能输出一个 JSON 对象，不要输出 Markdown 代码块或额外解释。
                        JSON 结构如下：
                        {
                          "title": "报告标题",
                          "suggestions": ["3 到 6 条摘要要点"],
                          "reportContent": "适合直接写入 PDF 的完整报告正文"
                        }

                        输出要求：
                        1. reportContent 必须是完整中文报告，包含标题、摘要、正文、小结和风险提示，格式适合直接导出 PDF。
                        2. 内容要忠于已有 AI 分析回答，不要编造新的事实或数据。
                        3. 如果原回答里存在不确定项，要在报告里明确写出“不确定”或“待进一步核实”。
                        4. title 要像正式报告标题，适合文件名展示。
                        5. suggestions 用简短中文提炼报告核心结论。
                        6. 语言要更正式、更适合存档，但不要空泛拔高。
                        7. reportContent 中禁止使用 Markdown 格式符号（例如 #、**、*、-、`），仅使用纯文本、中文标点和换行。
                        """)
                .user("""
                        报告面向用户：%s

                        原始用户问题：
                        %s

                        已有 AI 分析回答：
                        %s
                        """.formatted(
                        StringUtils.hasText(userName) ? userName.trim() : "用户",
                        StringUtils.hasText(message) ? message.trim() : "未提供",
                        sourceAnswer
                ))
                .call()
                .content();
            try {
                return OBJECT_MAPPER.readValue(extractStructuredReportJson(raw), StructuredFundReport.class);
            } catch (Exception ex) {
                throw new IllegalStateException("报告结构化内容解析失败", ex);
            }
        });
        FundReport fundReport = createFundReport(
                chatId,
                structuredFundReport.title(),
                structuredFundReport.suggestions(),
                structuredFundReport.reportContent()
        );
        if (persistReportMessage) {
            String reportReply = buildReportDirectReply(fundReport);
            chatMemoryMetadataService.recordAssistantReport(
                    chatId,
                    reportReply,
                    fundReport.title(),
                    fundReport.reportFileName(),
                    fundReport.reportDownloadPath()
            );
        }
        log.info("fundReport: {}", fundReport);
        return fundReport;
    }

    public String doChatWithRag(String message, String chatId) {
        return doChatWithRag(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public String doChatWithRag(String message, String chatId, String advisorStyle) {
        return doChatWithRag(message, chatId, advisorStyle, null);
    }

    public String doChatWithRag(String message, String chatId, String advisorStyle, String modelId) {
        AdvisorStyle style = resolveGeneralModeAdvisorStyle(advisorStyle, "rag");
        return doChatWithRagInternal(message, chatId, style, modelId, true);
    }

    private String doChatWithRagInternal(String message,
                                         String chatId,
                                         AdvisorStyle style,
                                         String modelId,
                                         boolean allowToolResolution) {
        String directReply = rejectOutOfDomainQuestion(chatId, message, "rag", modelId);
        if (directReply != null) {
            return directReply;
        }

        RetrievedRagContext ragContext = retrieveRagContext(message);
        List<ToolExecutionResult> toolResults = allowToolResolution && shouldAttemptToolResolution(message)
                ? resolveToolResults(message, modelId)
                : List.of();
        ChatTrace trace = buildTrace(toolResults, ragContext.documents());
        ChatResponse chatResponse = chatModelExecutionService.executeWithRetry("RAG 对话", () -> createConversationClient(modelId)
                .prompt()
                .system(buildSystemPrompt(
                        SYSTEM_PROMPT,
                        style,
                        RAG_PROMPT,
                        buildToolSynthesisPrompt(message, toolResults)
                ))
                .user(buildKnowledgeGroundedUserMessage(ragContext.context(), message))
                .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .chatResponse());
        String content = chatResponse.getResult().getOutput().getText();
        log.info("ragContext: {}", ragContext.context());
        log.info("content: {}", content);
        return finalizeReply(chatId, message, content, trace);
    }

    public Flux<String> doChatWithRagStream(String message, String chatId) {
        return doChatWithRagStream(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public Flux<String> doChatWithRagStream(String message, String chatId, String advisorStyle) {
        return doChatWithRagStream(message, chatId, advisorStyle, null);
    }

    public Flux<String> doChatWithRagStream(String message, String chatId, String advisorStyle, String modelId) {
        AdvisorStyle style = resolveGeneralModeAdvisorStyle(advisorStyle, "rag stream");
        return doChatWithRagStreamInternal(message, chatId, style, modelId, true);
    }

    private Flux<String> doChatWithRagStreamInternal(String message,
                                                     String chatId,
                                                     AdvisorStyle style,
                                                     String modelId,
                                                     boolean allowToolResolution) {
        String directReply = rejectOutOfDomainQuestion(chatId, message, "rag stream", modelId);
        if (directReply != null) {
            return Flux.just(directReply);
        }

        RetrievedRagContext ragContext = retrieveRagContext(message);
        List<ToolExecutionResult> toolResults = allowToolResolution && shouldAttemptToolResolution(message)
                ? resolveToolResults(message, modelId)
                : List.of();
        ChatTrace trace = buildTrace(toolResults, ragContext.documents());
        log.info("ragContext: {}", ragContext.context());
        return chatModelExecutionService.streamWithRetryAndFallback(
                        "RAG 流式对话",
                        () -> createConversationClient(modelId)
                                .prompt()
                                .system(buildSystemPrompt(
                                        SYSTEM_PROMPT,
                                        style,
                                        RAG_PROMPT,
                                        buildToolSynthesisPrompt(message, toolResults)
                                ))
                                .user(buildKnowledgeGroundedUserMessage(ragContext.context(), message))
                                .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                                .stream()
                                .content(),
                        () -> createConversationClient(modelId)
                                .prompt()
                                .system(buildSystemPrompt(
                                        SYSTEM_PROMPT,
                                        style,
                                        RAG_PROMPT,
                                        buildToolSynthesisPrompt(message, toolResults)
                                ))
                                .user(buildKnowledgeGroundedUserMessage(ragContext.context(), message))
                                .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                                .call()
                                .content()
                )
                .transform(stream -> finalizeStreamReply(stream, chatId, message, trace));
    }

    public String doChatWithTools(String message, String chatId) {
        return doChatWithTools(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public String doChatWithTools(String message, String chatId, String advisorStyle) {
        return doChatWithTools(message, chatId, advisorStyle, null);
    }

    public String doChatWithTools(String message, String chatId, String advisorStyle, String modelId) {
        String directReply = rejectOutOfDomainQuestion(chatId, message, "tools", modelId);
        if (directReply != null) {
            return directReply;
        }

        AdvisorStyle style = resolveToolsModeAdvisorStyle(advisorStyle, "tools");
        RetrievedRagContext ragContext = retrieveRagContext(message);
        List<ToolExecutionResult> toolResults = resolveToolResults(message, modelId);
        if (toolResults.isEmpty()) {
            return doChatWithRagInternal(message, chatId, style, modelId, false);
        }

        ChatResponse response = chatModelExecutionService.executeWithRetry("工具增强对话", () -> createConversationClient(modelId)
                .prompt()
                .system(buildSystemPrompt(SYSTEM_PROMPT, style, RAG_PROMPT, buildToolSynthesisPrompt(message, toolResults)))
                .user(buildKnowledgeGroundedUserMessage(ragContext.context(), message))
                .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .chatResponse());
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return finalizeReply(chatId, message, content, buildTrace(toolResults, ragContext.documents()));
    }

    public Flux<String> doChatWithToolsStream(String message, String chatId) {
        return doChatWithToolsStream(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public Flux<String> doChatWithToolsStream(String message, String chatId, String advisorStyle) {
        return doChatWithToolsStream(message, chatId, advisorStyle, null);
    }

    public Flux<String> doChatWithToolsStream(String message, String chatId, String advisorStyle, String modelId) {
        return Flux.defer(() -> {
        String directReply = rejectOutOfDomainQuestion(chatId, message, "tools stream", modelId);
            if (directReply != null) {
                return Flux.just(directReply);
            }

            AdvisorStyle style = resolveToolsModeAdvisorStyle(advisorStyle, "tools stream");
            RetrievedRagContext ragContext = retrieveRagContext(message);
            List<ToolExecutionResult> toolResults = resolveToolResults(message, modelId);
            if (toolResults.isEmpty()) {
                return doChatWithRagStreamInternal(message, chatId, style, modelId, false);
            }

            return chatModelExecutionService.streamWithRetryAndFallback(
                            "工具增强流式对话",
                            () -> createConversationClient(modelId)
                                    .prompt()
                                    .system(buildSystemPrompt(SYSTEM_PROMPT, style, RAG_PROMPT, buildToolSynthesisPrompt(message, toolResults)))
                                    .user(buildKnowledgeGroundedUserMessage(ragContext.context(), message))
                                    .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                                    .stream()
                                    .content(),
                            () -> createConversationClient(modelId)
                                    .prompt()
                                    .system(buildSystemPrompt(SYSTEM_PROMPT, style, RAG_PROMPT, buildToolSynthesisPrompt(message, toolResults)))
                                    .user(buildKnowledgeGroundedUserMessage(ragContext.context(), message))
                                    .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                                    .call()
                                    .content()
                    )
                    .transform(stream -> finalizeStreamReply(stream, chatId, message, buildTrace(toolResults, ragContext.documents())));
        });
    }

    boolean isFinanceRelatedQuestion(String message) {
        return financeQuestionClassifier.isFinanceRelatedQuestion(message);
    }

    boolean isFinanceRelatedQuestion(String chatId, String message, String modelId) {
        return financeConversationGuard.isFinanceRelatedQuestion(chatId, message, modelId);
    }

    private String rejectOutOfDomainQuestion(String chatId, String message, String modeName, String modelId) {
        if (isFinanceRelatedQuestion(chatId, message, modelId)) {
            return null;
        }

        log.info("Rejected non-finance question in {} mode, message={}", modeName, message);
        return FINANCE_ONLY_REPLY;
    }

    private FundReport rejectOutOfDomainReport(String chatId, String message, String modeName, String modelId) {
        if (isFinanceRelatedQuestion(chatId, message, modelId)) {
            return null;
        }

        log.info("Rejected non-finance question in {} mode, message={}", modeName, message);
        FundReport report = createFundReport(chatId, FINANCE_ONLY_REPORT_TITLE, FINANCE_ONLY_REPORT_SUGGESTIONS);
        return report;
    }

    private String extractStructuredReportJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("报告模型未返回内容");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("报告模型返回内容中未找到 JSON 对象");
        }
        return raw.substring(start, end + 1).trim();
    }

    private FundReport createFundReport(String chatId, String title, List<String> suggestions) {
        return createFundReport(chatId, title, suggestions, null);
    }

    private FundReport createFundReport(String chatId, String title, List<String> suggestions, String reportContent) {
        String normalizedTitle = StringUtils.hasText(title) ? title.trim() : "结构化投资报告";
        List<String> normalizedSuggestions = suggestions == null ? List.of() : suggestions;
        ReportDocumentService.GeneratedReportDocument generatedReportDocument =
                StringUtils.hasText(reportContent)
                        ? reportDocumentService.generateReportPdf(chatId, normalizedTitle, reportContent)
                        : reportDocumentService.generateReportPdf(chatId, normalizedTitle, normalizedSuggestions);
        return new FundReport(
                normalizedTitle,
                normalizedSuggestions,
                generatedReportDocument.displayFileName(),
                generatedReportDocument.downloadPath()
        );
    }

    private String resolveReportSourceAnswer(String message,
                                             String chatId,
                                             AdvisorStyle style,
                                             String modelId,
                                             String reportSourceContent,
                                             String mode) {
        if (StringUtils.hasText(reportSourceContent)) {
            return reportSourceContent.trim();
        }

        return switch (normalizeReportMode(mode)) {
            case "chat" -> doChat(message, chatId, style.code(), modelId);
            case "rag" -> doChatWithRag(message, chatId, style.code(), modelId);
            case "tools" -> doChatWithTools(message, chatId, style.code(), modelId);
            case "value" -> valueAnalysisService.analyze(message, chatId, style.code(), modelId);
            default -> valueAnalysisService.analyze(message, chatId, style.code(), modelId);
        };
    }

    private String buildReportDirectReply(FundReport report) {
        String suggestions = report.suggestions() == null || report.suggestions().isEmpty()
                ? "- 暂无建议"
                : report.suggestions().stream()
                .map(item -> "- " + item)
                .collect(Collectors.joining("\n"));
        return """
                %s

                %s
                """.formatted(
                report.title(),
                suggestions
        ).trim();
    }

    private String buildKnowledgeGroundedUserMessage(String ragContext, String message) {
        return """
                %s

                用户问题：
                %s
                """.formatted(ragContext, message);
    }

    private AdvisorStyle resolveGeneralModeAdvisorStyle(String advisorStyle, String modeName) {
        return resolveAdvisorStyle(advisorStyle, modeName, false);
    }

    private AdvisorStyle resolveToolsModeAdvisorStyle(String advisorStyle, String modeName) {
        return resolveAdvisorStyle(advisorStyle, modeName, true);
    }

    private AdvisorStyle resolveAdvisorStyle(String advisorStyle, String modeName, boolean allowBuffettStyle) {
        AdvisorStyle style = AdvisorStyle.resolve(advisorStyle);
        if (allowBuffettStyle || !style.isBuffettStyle()) {
            return style;
        }

        log.info("Advisor style {} is limited to value mode, fallback to default in {}", style.code(), modeName);
        return AdvisorStyle.DEFAULT;
    }

    private AdvisorStyle resolveReportSourceAdvisorStyle(String advisorStyle, String reportMode) {
        String normalizedReportMode = normalizeReportMode(reportMode);
        if ("value".equals(normalizedReportMode)) {
            return AdvisorStyle.resolve(advisorStyle);
        }
        if ("tools".equals(normalizedReportMode)) {
            return resolveToolsModeAdvisorStyle(advisorStyle, "report tools");
        }
        return resolveGeneralModeAdvisorStyle(advisorStyle, "report");
    }

    private String normalizeReportMode(String mode) {
        String normalized = String.valueOf(mode).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "chat", "rag", "tools", "value" -> normalized;
            default -> "value";
        };
    }

    private RetrievedRagContext retrieveRagContext(String query) {
        List<FundAppLocalDocumentRetriever.RetrievedDocument> documents = localDocumentRetriever.search(query, RAG_REFERENCE_LIMIT);
        if (documents.isEmpty()) {
            return new RetrievedRagContext(
                    "未检索到直接相关的本地资料。请明确告知用户当前回答主要基于通用知识，而不是本地知识库。",
                    List.of()
            );
        }

        StringBuilder builder = new StringBuilder("以下内容来自本地知识库，请优先据此回答：\n");
        for (int index = 0; index < documents.size(); index++) {
            FundAppLocalDocumentRetriever.RetrievedDocument document = documents.get(index);
            builder.append(index + 1)
                    .append(". 来源：")
                    .append(document.source())
                    .append('\n')
                    .append("片段：")
                    .append(document.snippet())
                    .append('\n');
        }
        return new RetrievedRagContext(builder.toString().trim(), documents);
    }

    private ChatTrace buildTrace(List<ToolExecutionResult> toolResults,
                                 List<FundAppLocalDocumentRetriever.RetrievedDocument> ragDocuments) {
        List<ChatTraceToolCall> toolCalls = toolResults == null ? List.of() : toolResults.stream()
                .map(result -> new ChatTraceToolCall(
                        null,
                        StringUtils.hasText(result.toolName()) ? result.toolName().trim() : null,
                        abbreviate(result.argumentsJson(), MAX_TRACE_ARGUMENT_LENGTH),
                        normalizeValue(result.output())
                ))
                .filter(item -> StringUtils.hasText(item.toolName())
                        || StringUtils.hasText(item.arguments())
                        || StringUtils.hasText(item.outputPreview()))
                .toList();
        List<ChatTraceReference> ragReferences = ragDocuments == null ? List.of() : ragDocuments.stream()
                .map(document -> new ChatTraceReference(
                        StringUtils.hasText(document.source()) ? document.source().trim() : null,
                        abbreviate(document.snippet(), MAX_TRACE_OUTPUT_PREVIEW_LENGTH)
                ))
                .filter(item -> StringUtils.hasText(item.source()) || StringUtils.hasText(item.snippet()))
                .toList();

        ChatTrace trace = new ChatTrace(toolCalls, ragReferences);
        return trace.isEmpty() ? null : trace;
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replace("\r", "").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength).trim() + "\n...(已截断)";
    }

    private String normalizeValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.replace("\r", "").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String finalizeReply(String chatId, String userMessage, String content, ChatTrace trace) {
        chatMemoryMetadataService.enrichLatestExchange(chatId, userMessage, trace);
        return content;
    }

    private Flux<String> finalizeStreamReply(Flux<String> stream, String chatId, String userMessage, ChatTrace trace) {
        return stream.doOnComplete(() -> chatMemoryMetadataService.enrichLatestExchange(chatId, userMessage, trace));
    }

    private List<ToolExecutionResult> resolveToolResults(String message, String modelId) {
        List<ToolExecutionResult> allResults = new ArrayList<>();
        Set<String> executedSignatures = new LinkedHashSet<>();
        int remainingCallBudget = TOOL_LOOP_LIMIT.maxTotalCalls();
        ChatClient toolPlannerClient = createToolPlannerClient(modelId);
        List<PlannedToolCall> plannedCalls = planInitialToolCalls(message, toolPlannerClient);

        for (int round = 1; round <= TOOL_LOOP_LIMIT.maxRounds(); round++) {
            if (plannedCalls.isEmpty() || remainingCallBudget <= 0) {
                break;
            }

            List<PlannedToolCall> newCalls = selectNewToolCalls(plannedCalls, executedSignatures, remainingCallBudget);
            if (newCalls.isEmpty()) {
                log.info("No new tool calls remain after deduplication in round {}, message={}", round, message);
                break;
            }

            List<ToolExecutionResult> roundResults = executeToolCalls(newCalls);
            allResults.addAll(roundResults);
            remainingCallBudget -= roundResults.size();
            newCalls.stream()
                    .map(call -> buildToolCallSignature(call.toolName(), call.argumentsJson()))
                    .forEach(executedSignatures::add);

            if (round >= TOOL_LOOP_LIMIT.maxRounds() || remainingCallBudget <= 0) {
                break;
            }

            plannedCalls = planContinuationToolCalls(message, allResults, executedSignatures, round + 1, remainingCallBudget, toolPlannerClient);
        }

        return allResults;
    }

    private String buildToolRouterPrompt() {
        return TOOL_ROUTER_PROMPT.formatted(
                formatInitialRoundLimitInstruction(),
                buildToolCatalog()
        );
    }

    private List<PlannedToolCall> planInitialToolCalls(String message, ChatClient toolPlannerClient) {
        String plannerResponse;
        try {
            plannerResponse = chatModelExecutionService.executeWithRetry("工具规划", () -> toolPlannerClient
                    .prompt()
                    .system(buildToolRouterPrompt())
                    .user(message)
                    .call()
                    .content(), false);
        } catch (RuntimeException ex) {
            log.warn("Tool planner failed, falling back to heuristic tool routing. message={}", message);
            log.debug("Tool planner failure for message={}", message, ex);
            return buildHeuristicToolCalls(message, roundToolCallLimit(TOOL_LOOP_LIMIT.maxTotalCalls()));
        }

        log.info("initial tool planner response: {}", plannerResponse);

        List<PlannedToolCall> plannedCalls = parsePlannedToolCalls(plannerResponse, roundToolCallLimit(TOOL_LOOP_LIMIT.maxTotalCalls()));
        if (plannedCalls.isEmpty()) {
            plannedCalls = buildHeuristicToolCalls(message, roundToolCallLimit(TOOL_LOOP_LIMIT.maxTotalCalls()));
        }
        return plannedCalls;
    }

    private List<PlannedToolCall> planContinuationToolCalls(String message,
                                                            List<ToolExecutionResult> currentResults,
                                                            Set<String> executedSignatures,
                                                            int nextRound,
                                                            int remainingCallBudget,
                                                            ChatClient toolPlannerClient) {
        if (currentResults == null || currentResults.isEmpty() || remainingCallBudget <= 0) {
            return List.of();
        }

        String plannerResponse;
        try {
            plannerResponse = chatModelExecutionService.executeWithRetry("循环补查规划", () -> toolPlannerClient
                    .prompt()
                    .system(buildToolContinuationRouterPrompt(message, currentResults, executedSignatures, nextRound, remainingCallBudget))
                    .user(message)
                    .call()
                    .content(), false);
        } catch (RuntimeException ex) {
            log.warn("Continuation planner failed, stopping extra tool rounds. round={}", nextRound);
            log.debug("Continuation planner failure for round={}", nextRound, ex);
            return List.of();
        }

        log.info("continuation tool planner response round {}: {}", nextRound, plannerResponse);
        int roundLimit = roundToolCallLimit(remainingCallBudget);
        return limitPlannedCalls(parsePlannedToolCalls(plannerResponse, roundLimit), roundLimit);
    }

    private String buildToolContinuationRouterPrompt(String message,
                                                     List<ToolExecutionResult> currentResults,
                                                     Set<String> executedSignatures,
                                                     int nextRound,
                                                     int remainingCallBudget) {
        return TOOL_CONTINUATION_ROUTER_PROMPT.formatted(
                nextRound,
                TOOL_LOOP_LIMIT.maxRounds(),
                formatContinuationRoundLimitSummary(remainingCallBudget),
                TOOL_LOOP_LIMIT.maxTotalCalls(),
                StringUtils.hasText(message) ? message.trim() : "",
                formatExecutedSignatures(executedSignatures),
                formatToolResultsForPlanning(currentResults),
                formatContinuationRoundLimitInstruction(remainingCallBudget),
                buildToolCatalog()
        );
    }

    private String buildToolCatalog() {
        return Arrays.stream(allTools)
                .map(tool -> """
                        - 工具名：%s
                          描述：%s
                          参数 JSON Schema：%s
                        """.formatted(
                        tool.getName(),
                        tool.getDescription(),
                        tool.getInputTypeSchema()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String formatExecutedSignatures(Set<String> executedSignatures) {
        if (executedSignatures == null || executedSignatures.isEmpty()) {
            return "- 暂无";
        }
        return executedSignatures.stream()
                .map(signature -> "- " + signature)
                .collect(Collectors.joining("\n"));
    }

    private String formatToolResultsForPlanning(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "- 暂无";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < toolResults.size(); index++) {
            ToolExecutionResult result = toolResults.get(index);
            builder.append("[").append(index + 1).append("]").append('\n')
                    .append("工具：").append(result.toolName()).append('\n')
                    .append("调用参数：").append(result.argumentsJson()).append('\n')
                    .append("工具输出：").append(normalizeValue(result.output())).append('\n');
        }
        return builder.toString().trim();
    }

    private List<PlannedToolCall> selectNewToolCalls(List<PlannedToolCall> plannedCalls,
                                                     Set<String> executedSignatures,
                                                     int remainingCallBudget) {
        if (plannedCalls == null || plannedCalls.isEmpty() || remainingCallBudget <= 0) {
            return List.of();
        }

        List<PlannedToolCall> newCalls = new ArrayList<>();
        Set<String> roundSignatures = new LinkedHashSet<>();
        int roundLimit = roundToolCallLimit(remainingCallBudget);

        for (PlannedToolCall plannedCall : plannedCalls) {
            if (plannedCall == null || !StringUtils.hasText(plannedCall.toolName())) {
                continue;
            }

            String signature = buildToolCallSignature(plannedCall.toolName(), plannedCall.argumentsJson());
            if (executedSignatures.contains(signature) || !roundSignatures.add(signature)) {
                continue;
            }

            newCalls.add(new PlannedToolCall(plannedCall.toolName(), normalizeArgumentsJson(plannedCall.argumentsJson())));
            if (newCalls.size() >= roundLimit) {
                break;
            }
        }

        return newCalls;
    }

    private int roundToolCallLimit(int remainingCallBudget) {
        if (remainingCallBudget <= 0) {
            return 0;
        }
        if (TOOL_LOOP_LIMIT.maxCallsPerRound() <= 0) {
            return remainingCallBudget;
        }
        return Math.max(0, Math.min(remainingCallBudget, TOOL_LOOP_LIMIT.maxCallsPerRound()));
    }

    private String formatInitialRoundLimitInstruction() {
        if (TOOL_LOOP_LIMIT.maxCallsPerRound() <= 0) {
            return "本轮调用次数不设单独上限，但总计最多只能调用 %d 次工具。".formatted(TOOL_LOOP_LIMIT.maxTotalCalls());
        }
        return "单轮最多只规划 %d 个工具调用。".formatted(TOOL_LOOP_LIMIT.maxCallsPerRound());
    }

    private String formatContinuationRoundLimitSummary(int remainingCallBudget) {
        if (TOOL_LOOP_LIMIT.maxCallsPerRound() <= 0) {
            return "本轮不设单独次数上限，受剩余总预算 %d 次约束".formatted(Math.max(0, remainingCallBudget));
        }
        return "本轮最多 %d 次".formatted(roundToolCallLimit(remainingCallBudget));
    }

    private String formatContinuationRoundLimitInstruction(int remainingCallBudget) {
        if (TOOL_LOOP_LIMIT.maxCallsPerRound() <= 0) {
            return "本轮可继续规划多个工具调用，但总计不能超过剩余预算 %d 次。".formatted(Math.max(0, remainingCallBudget));
        }
        return "最多可继续规划 %d 个工具调用。".formatted(roundToolCallLimit(remainingCallBudget));
    }

    private List<PlannedToolCall> limitPlannedCalls(List<PlannedToolCall> plannedCalls, int limit) {
        if (plannedCalls == null || plannedCalls.isEmpty() || limit <= 0) {
            return List.of();
        }
        return plannedCalls.stream().limit(limit).toList();
    }

    private String buildToolCallSignature(String toolName, String argumentsJson) {
        return normalizeToolName(toolName) + "|" + normalizeArgumentsJson(argumentsJson);
    }

    private String normalizeArgumentsJson(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return "{}";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(argumentsJson);
            return OBJECT_MAPPER.writeValueAsString(sortJsonNode(root));
        } catch (Exception ignored) {
            return argumentsJson.replace("\r", "").replaceAll("\\s+", " ").trim();
        }
    }

    private JsonNode sortJsonNode(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
            node.forEach(child -> arrayNode.add(sortJsonNode(child)));
            return arrayNode;
        }

        ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        fieldNames.stream()
                .sorted()
                .forEach(fieldName -> objectNode.set(fieldName, sortJsonNode(node.get(fieldName))));
        return objectNode;
    }

    private List<PlannedToolCall> parsePlannedToolCalls(String plannerResponse, int limit) {
        List<PlannedToolCall> jsonCalls = parseJsonToolPlan(plannerResponse, limit);
        if (!jsonCalls.isEmpty()) {
            return jsonCalls;
        }

        return parseLegacyToolCalls(plannerResponse, limit);
    }

    private List<PlannedToolCall> parseJsonToolPlan(String plannerResponse, int limit) {
        String json = extractFirstJsonObject(plannerResponse);
        if (!StringUtils.hasText(json)) {
            return List.of();
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (!root.path("needTool").asBoolean(false) || !root.path("calls").isArray()) {
                return List.of();
            }

            List<PlannedToolCall> calls = new ArrayList<>();
            for (JsonNode callNode : root.path("calls")) {
                String toolName = callNode.path("tool").asText("");
                JsonNode arguments = callNode.path("arguments");
                if (!StringUtils.hasText(toolName) || arguments.isMissingNode() || arguments.isNull()) {
                    continue;
                }

                calls.add(new PlannedToolCall(toolName, OBJECT_MAPPER.writeValueAsString(arguments)));
                if (calls.size() >= limit) {
                    break;
                }
            }
            return calls;
        } catch (Exception ex) {
            log.warn("Failed to parse tool JSON plan: {}", plannerResponse, ex);
            return List.of();
        }
    }

    private List<PlannedToolCall> parseLegacyToolCalls(String plannerResponse, int limit) {
        String normalized = normalizePlannerResponse(plannerResponse);
        List<PlannedToolCall> calls = new ArrayList<>();
        int cursor = 0;

        while (cursor < normalized.length() && calls.size() < limit) {
            int begin = indexOfIgnoreCase(normalized, "tool call begin", cursor);
            int segmentStart = begin >= 0 ? begin : cursor;
            int jsonStart = normalized.indexOf('{', segmentStart);
            if (jsonStart < 0) {
                break;
            }

            String json = extractFirstJsonObject(normalized.substring(jsonStart));
            if (!StringUtils.hasText(json)) {
                break;
            }

            String toolName = extractLegacyToolName(normalized.substring(segmentStart, jsonStart));
            if (StringUtils.hasText(toolName)) {
                calls.add(new PlannedToolCall(toolName, json));
            }

            cursor = jsonStart + json.length();
        }

        return calls;
    }

    private List<PlannedToolCall> buildHeuristicToolCalls(String message, int limit) {
        try {
            List<PlannedToolCall> plannedCalls = new ArrayList<>();

            if (needsCurrentTimeTool(message) && findToolCallback("Get current time") != null) {
                String argumentsJson = OBJECT_MAPPER.writeValueAsString(new CurrentTimeArguments(message));
                plannedCalls.add(new PlannedToolCall("Get current time", argumentsJson));
            }

            if (isStockQuoteQuestion(message) && findToolCallback("Get realtime stock quote") != null) {
                String argumentsJson = OBJECT_MAPPER.writeValueAsString(new StockQuoteArguments(message));
                plannedCalls.add(new PlannedToolCall("Get realtime stock quote", argumentsJson));
            }

            if (needsFinancialReportTool(message) && findToolCallback("Search A-share financial reports") != null) {
                String argumentsJson = OBJECT_MAPPER.writeValueAsString(buildFinancialReportArguments(message));
                plannedCalls.add(new PlannedToolCall("Search A-share financial reports", argumentsJson));
            }

            if (needsSearchTool(message) && findToolCallback("Search for information") != null) {
                String searchQuery = buildRealtimeSearchQuery(message);
                String argumentsJson = OBJECT_MAPPER.writeValueAsString(new SearchArguments(searchQuery));
                plannedCalls.add(new PlannedToolCall("Search for information", argumentsJson));
            }

            if (plannedCalls.size() > limit) {
                return plannedCalls.subList(0, limit);
            }
            return plannedCalls;
        } catch (Exception ex) {
            log.warn("Failed to build heuristic tool call for message: {}", message, ex);
            return List.of();
        }
    }

    private List<PlannedToolCall> buildHeuristicToolCalls(String message) {
        return buildHeuristicToolCalls(message, roundToolCallLimit(TOOL_LOOP_LIMIT.maxTotalCalls()));
    }

    private boolean isStockQuoteQuestion(String message) {
        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        return normalized.contains("股价")
                || normalized.contains("行情")
                || normalized.contains("报价")
                || normalized.contains("现价")
                || normalized.contains("涨跌")
                || normalized.contains("开盘")
                || normalized.contains("收盘")
                || normalized.contains("成交量")
                || normalized.contains("price")
                || normalized.contains("quote")
                || normalized.contains("market")
                || normalized.contains("intraday");
    }

    private boolean needsCurrentTimeTool(String message) {
        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "当前时间", "现在几点", "几点了", "当前日期", "今天几号", "今天星期几", "今天周几", "当地时间", "时区",
                "current time", "what time", "today date", "timezone")
                || containsAny(normalized,
                "今天", "昨天", "明天", "本周", "上周", "本月", "上月", "本季度", "上季度", "今年", "去年",
                "截至今天", "截至昨日", "截至目前", "截至现在");
    }

    private boolean needsFinancialReportTool(String message) {
        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        boolean primaryFinancialReportIntent = normalized.contains("财报")
                || normalized.contains("年报")
                || normalized.contains("季报")
                || normalized.contains("半年报")
                || normalized.contains("一季报")
                || normalized.contains("三季报")
                || normalized.contains("财务报表")
                || normalized.contains("利润表")
                || normalized.contains("资产负债表")
                || normalized.contains("现金流量表");
        if (primaryFinancialReportIntent) {
            return true;
        }

        boolean metricIntent = normalized.contains("营收")
                || normalized.contains("营业收入")
                || normalized.contains("净利润")
                || normalized.contains("扣非")
                || normalized.contains("毛利率")
                || normalized.contains("现金流")
                || normalized.contains("现金及其等价物")
                || normalized.contains("货币资金")
                || normalized.contains("带息债务")
                || normalized.contains("资产负债率")
                || normalized.contains("总资产周转率")
                || normalized.contains("财务质量");
        boolean roeIntent = (normalized.contains("roe") || normalized.contains("roic"))
                && (containsAny(normalized, "最近", "近", "过去", "五年", "5年", "三年", "3年")
                || ASHARE_CODE_PATTERN.matcher(String.valueOf(message)).find());
        return metricIntent || roeIntent;
    }

    private boolean needsSearchTool(String message) {
        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        return normalized.contains("公告")
                || normalized.contains("新闻")
                || normalized.contains("资讯")
                || normalized.contains("研报")
                || normalized.contains("链接")
                || normalized.contains("官网")
                || normalized.contains("网页")
                || normalized.contains("网址")
                || normalized.contains("外部数据")
                || normalized.contains("latest")
                || normalized.contains("news")
                || normalized.contains("filing")
                || normalized.contains("press release");
    }

    private boolean shouldAttemptToolResolution(String message) {
        return needsCurrentTimeTool(message)
                || isStockQuoteQuestion(message)
                || needsFinancialReportTool(message)
                || needsSearchTool(message);
    }

    private String buildRealtimeSearchQuery(String message) {
        String trimmed = StringUtils.trimWhitespace(message);
        if (!StringUtils.hasText(trimmed)) {
            return "最新 公告 新闻";
        }

        if (trimmed.contains("公告") || trimmed.contains("新闻") || trimmed.contains("资讯")) {
            return trimmed + " 最新 公告 新闻";
        }

        if (trimmed.contains("研报") || trimmed.contains("官网") || trimmed.contains("链接")) {
            return trimmed + " 官方链接 研报 公告";
        }

        return trimmed + " 最新 外部公开信息";
    }

    private FinancialReportArguments buildFinancialReportArguments(String message) {
        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        String period = detectFinancialReportPeriod(normalized);
        int limit = "latest".equals(period) ? 1 : detectFinancialReportLimit(message, period);
        String focus = detectFinancialReportFocus(normalized);
        return new FinancialReportArguments(message, limit, period, focus);
    }

    private String detectFinancialReportPeriod(String normalizedMessage) {
        if (containsAny(normalizedMessage, "全部", "所有年份", "历年", "完整历史", "all")) {
            return "all";
        }
        if (containsAny(normalizedMessage, "最新一期", "最新财报", "当前财报", "最近一期", "latest")) {
            return "latest";
        }
        return "annual";
    }

    private int detectFinancialReportLimit(String message, String period) {
        if ("latest".equals(period)) {
            return 1;
        }

        Matcher matcher = YEAR_LIMIT_PATTERN.matcher(String.valueOf(message));
        if (matcher.find()) {
            int parsedLimit = Integer.parseInt(matcher.group(2));
            return Math.min(Math.max(parsedLimit, 1), 8);
        }

        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        if (normalized.contains("五年")) {
            return 5;
        }
        if (normalized.contains("三年")) {
            return 3;
        }
        return "all".equals(period) ? 8 : 5;
    }

    private String detectFinancialReportFocus(String normalizedMessage) {
        boolean incomeOnly = containsAny(normalizedMessage, "利润表", "营业成本", "销售费用", "管理费用", "研发费用", "财务费用")
                && !containsAny(normalizedMessage, "资产负债率", "带息债务", "资产负债表", "现金流量表", "现金流");
        if (incomeOnly) {
            return "income";
        }

        boolean balanceOnly = containsAny(normalizedMessage, "资产负债表", "流动资产", "固定资产", "商誉", "总负债")
                && !containsAny(normalizedMessage, "现金流量表", "现金流", "利润表");
        if (balanceOnly) {
            return "balance";
        }

        boolean cashflowOnly = containsAny(normalizedMessage, "现金流量表", "经营现金流", "投资现金流", "筹资现金流")
                && !containsAny(normalizedMessage, "资产负债表", "利润表", "带息债务", "资产负债率");
        if (cashflowOnly) {
            return "cashflow";
        }

        return "core";
    }

    private boolean containsAny(String text, String... candidates) {
        String normalized = String.valueOf(text);
        for (String candidate : candidates) {
            if (normalized.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalizePlannerResponse(String plannerResponse) {
        return String.valueOf(plannerResponse)
                .replace('｜', '|')
                .replace('▁', ' ')
                .replace("\r", "");
    }

    private String extractLegacyToolName(String segment) {
        String cleaned = segment
                .replaceAll("(?is)<\\|.*?\\|>", " ")
                .replaceAll("(?is)tool\\s*calls?\\s*begin", " ")
                .replaceAll("(?is)tool\\s*calls?\\s*end", " ")
                .replaceAll("(?is)tool\\s*sep", " ")
                .replaceAll("(?is)function", " ")
                .replace("`", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return cleaned;
    }

    private String extractFirstJsonObject(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }

        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }

            if (current == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1);
                }
            }
        }

        return null;
    }

    private int indexOfIgnoreCase(String text, String search, int fromIndex) {
        return text.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT), fromIndex);
    }

    private List<ToolExecutionResult> executeToolCalls(List<PlannedToolCall> plannedCalls) {
        List<ToolExecutionResult> results = new ArrayList<>();

        for (PlannedToolCall plannedCall : plannedCalls) {
            ToolCallback toolCallback = findToolCallback(plannedCall.toolName());
            if (toolCallback == null) {
                results.add(new ToolExecutionResult(
                        plannedCall.toolName(),
                        plannedCall.argumentsJson(),
                        "工具未找到，无法执行。"
                ));
                continue;
            }

            try {
                String toolOutput = toolCallback.call(plannedCall.argumentsJson());
                results.add(new ToolExecutionResult(
                        toolCallback.getName(),
                        plannedCall.argumentsJson(),
                        toolOutput
                ));
            } catch (Exception ex) {
                log.error("Failed to execute tool: {}", plannedCall.toolName(), ex);
                results.add(new ToolExecutionResult(
                        toolCallback.getName(),
                        plannedCall.argumentsJson(),
                        "工具执行失败：" + ex.getMessage()
                ));
            }
        }

        log.info("tool execution results: {}", results);
        return results;
    }

    private ToolCallback findToolCallback(String requestedToolName) {
        String normalizedRequestedName = normalizeToolName(requestedToolName);
        ToolCallback matchedTool = Arrays.stream(allTools)
                .filter(tool -> normalizeToolName(tool.getName()).equals(normalizedRequestedName))
                .findFirst()
                .orElse(null);
        if (matchedTool == null) {
            log.warn("Tool not found: requested={}, available={}",
                    requestedToolName,
                    Arrays.stream(allTools).map(ToolCallback::getName).toList());
        }
        return matchedTool;
    }

    private String normalizeToolName(String toolName) {
        return String.valueOf(toolName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "");
    }

    private String buildToolSynthesisPrompt(String message, List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return null;
        }

        String formattedResults = toolResults.stream()
                .map(result -> """
                        工具：%s
                        调用参数：%s
                        工具输出：
                        %s
                        """.formatted(result.toolName(), result.argumentsJson(), result.output()))
                .collect(Collectors.joining("\n"));

        return TOOL_SYNTHESIS_PROMPT.formatted(
                StringUtils.hasText(message) ? message.trim() : "",
                summarizeCurrentTimeToolResult(toolResults),
                formattedResults
        );
    }

    private String summarizeCurrentTimeToolResult(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "未查询到当前时间工具结果。";
        }

        String normalizedCurrentTimeToolName = normalizeToolName("Get current time");
        for (ToolExecutionResult result : toolResults) {
            if (result == null || !normalizedCurrentTimeToolName.equals(normalizeToolName(result.toolName()))) {
                continue;
            }
            String output = normalizeValue(result.output());
            if (!StringUtils.hasText(output)) {
                return "已调用当前时间工具，但返回内容为空。";
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(output);
                String currentTime = firstNonBlank(
                        root.path("currentTime").asText(""),
                        root.path("isoTime").asText(""),
                        root.path("serverTime").asText("")
                );
                String timezone = firstNonBlank(root.path("timezone").asText(""), root.path("serverTimezone").asText(""));
                String offset = root.path("offset").asText("");
                if (StringUtils.hasText(currentTime) || StringUtils.hasText(timezone)) {
                    return "已校验当前时间：%s（时区：%s%s）".formatted(
                            firstNonBlank(currentTime, "未返回具体时间"),
                            firstNonBlank(timezone, "未返回时区"),
                            StringUtils.hasText(offset) ? "，偏移：" + offset : ""
                    );
                }
            } catch (Exception ignored) {
                return output;
            }
            return output;
        }
        return "未查询到当前时间工具结果。";
    }

    private String buildSystemPrompt(String basePrompt, AdvisorStyle style, String... sections) {
        List<String> promptSections = new ArrayList<>();
        if (StringUtils.hasText(basePrompt)) {
            promptSections.add(basePrompt.trim());
        }
        promptSections.add(TIME_AWARENESS_PROMPT.trim());
        if (style != null && style.hasSystemPrompt()) {
            promptSections.add(style.systemPrompt());
        }
        for (String section : sections) {
            if (StringUtils.hasText(section)) {
                promptSections.add(section.trim());
            }
        }
        return promptSections.isEmpty() ? null : String.join("\n\n", promptSections);
    }

    private void recordDirectReply(String chatId, String userMessage, String assistantReply) {
        chatMemoryMetadataService.recordExchange(chatId, userMessage, assistantReply, null);
    }

    private ChatClient createConversationClient(String modelId) {
        return ChatClient.builder(chatModelRegistryService.getChatModel(modelId))
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .chatMemoryRetrieveSize(20)
                                .build(),
                        new MyLoggerAdvisor()
                )
                .build();
    }

    private ChatClient createStandaloneClient(String modelId) {
        return ChatClient.builder(chatModelRegistryService.getChatModel(modelId))
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    private ChatClient createToolPlannerClient(String modelId) {
        return ChatClient.builder(chatModelRegistryService.getInternalTaskChatModel(modelId))
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    private static ChatModelRegistryService createCompatibilityChatModelRegistry(ChatModel chatModel) {
        return new ChatModelRegistryService(new ChatModelProperties()) {
            @Override
            public ChatModel getChatModel(String requestedModelId) {
                return chatModel;
            }

            @Override
            public ChatModel getInternalTaskChatModel(String requestedModelId) {
                return chatModel;
            }

            @Override
            public synchronized String resolveModelId(String requestedModelId) {
                return "compatibility-chat-model";
            }
        };
    }

    private static ValueAnalysisService createCompatibilityValueAnalysisService(ChatModel chatModel,
                                                                                ChatMemory chatMemory,
                                                                                FundAppLocalDocumentRetriever localDocumentRetriever,
                                                                                FinanceQuestionClassifier financeQuestionClassifier,
                                                                                ChatMemoryMetadataService chatMemoryMetadataService) {
        return new ValueAnalysisService(
                chatMemory,
                localDocumentRetriever,
                financeQuestionClassifier,
                new ToolCallback[0],
                chatMemoryMetadataService,
                new ChatModelExecutionService(),
                createCompatibilityChatModelRegistry(chatModel)
        );
    }

    private record PlannedToolCall(String toolName, String argumentsJson) {
    }

    private record ToolExecutionResult(String toolName, String argumentsJson, String output) {
    }

    private record SearchArguments(String query) {
    }

    private record CurrentTimeArguments(String timezoneHint) {
    }

    private record StockQuoteArguments(String stockQuery) {
    }

    private record FinancialReportArguments(String stockQuery, Integer limit, String period, String focus) {
    }

    private record RetrievedRagContext(String context,
                                       List<FundAppLocalDocumentRetriever.RetrievedDocument> documents) {
    }
}
