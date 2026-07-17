package com.aweqy.aiagent.service;

import com.aweqy.aiagent.advisor.MyLoggerAdvisor;
import com.aweqy.aiagent.config.ChatModelProperties;
import com.aweqy.aiagent.config.ToolLoopRuntimeConfig;
import com.aweqy.aiagent.model.AdvisorStyle;
import com.aweqy.aiagent.model.ChatTrace;
import com.aweqy.aiagent.model.ChatTraceReference;
import com.aweqy.aiagent.model.ChatTraceToolCall;
import com.aweqy.aiagent.model.ToolLoopLimit;
import com.aweqy.aiagent.rag.FundAppLocalDocumentRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ValueAnalysisService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_SEARCH_CALLS = 6;
    private static final ToolLoopLimit TOOL_LOOP_LIMIT = ToolLoopRuntimeConfig.VALUE_ANALYSIS;
    private static final int MAX_TRACE_ARGUMENT_LENGTH = 360;
    private static final int MAX_TRACE_OUTPUT_PREVIEW_LENGTH = 1_200;
    private static final int MAX_RAG_REFERENCES = 3;
    private static final int FINANCIAL_ANALYSIS_YEAR_LIMIT = 3;
    private static final String FINANCIAL_ANALYSIS_WINDOW_LABEL = "近三年";
    private static final String FINANCIAL_ANALYSIS_QUERY_WINDOW = "最近3年";

    private static final Set<String> NON_STOCK_KEYWORDS = Set.of(
            "基金", "etf", "lof", "fof", "reits", "指数", "上证指数", "深证成指", "创业板指",
            "沪深300", "中证500", "中证1000", "恒生指数", "纳斯达克指数", "标普500", "道琼斯",
            "行业", "板块", "赛道", "概念", "主题", "大盘", "债券", "可转债", "黄金", "原油", "期货"
    );
    private static final Set<String> MULTI_TARGET_HINTS = Set.of(
            "对比", "比较", "vs", "versus", "pk", "哪个好", "谁更", "哪个更", "二选一"
    );
    private static final Set<String> COMPANY_NAME_STOP_WORDS = Set.of(
            "最近", "近年", "三年", "五年", "财报", "年报", "季报", "半年报", "扣非", "roe", "roic", "净利润",
            "毛利率", "营业收入", "资产负债率", "带息债务", "现金", "总资产", "总负债", "周转率",
            "现金流", "等价物", "竞争对手", "明细", "分析", "步骤"
    );
    private static final Set<String> ENGLISH_TICKER_STOP_WORDS = Set.of(
            "AI", "API", "HTTP", "HTTPS", "HTML", "JSON", "JAVA", "SQL", "PDF", "TXT",
            "DOC", "PPT", "PPTX", "CSV", "XML", "ETF", "LOF", "REITS", "INDEX", "VALUE",
            "ROE", "ROIC", "PE", "PB", "EPS", "CEO", "CFO", "OK", "HI", "HELLO", "HELP", "CODE"
    );

    private static final Pattern EXACT_STOCK_IDENTIFIER_PATTERN = Pattern.compile(
            "(?i)^(?:sh|sz|bj)\\d{6}$|^(?:hk)\\d{5}$|^(?:gb_)[A-Z][A-Z0-9\\.-]{0,9}$"
                    + "|^\\d{6}(?:\\.(?:SH|SZ|BJ))?$|^\\d{5}(?:\\.HK)?$|^[A-Z][A-Z0-9\\.-]{0,4}(?:\\.US)?$"
    );
    private static final Pattern EMBEDDED_STOCK_IDENTIFIER_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:sh|sz|bj)\\d{6}\\b|\\bhk\\d{5}\\b|\\bgb_[A-Z][A-Z0-9\\.-]{0,9}\\b"
                    + "|\\b\\d{6}(?:\\.(?:SH|SZ|BJ))?\\b|\\b\\d{5}(?:\\.HK)?\\b|\\b[A-Z][A-Z0-9\\.-]{0,4}(?:\\.US)?\\b)"
    );
    private static final Pattern MULTI_TARGET_SEPARATOR_PATTERN = Pattern.compile("(?i)\\s*(?:,|，|、|/|\\\\|vs|和|与|跟)\\s*");

    private static final String VALUE_MODE_OUT_OF_DOMAIN_REPLY = """
            当前价值分析模式只支持单只股票或单家上市公司。
            请直接给我一个明确标的，例如“贵州茅台”“600519”“腾讯控股”或“AAPL”，我会先做近三年财务体检，再按周行五步法展开分析。
            """.trim();

    private static final String VALUE_MODE_NON_STOCK_REPLY = """
            当前价值分析模式已经改成只分析单只股票，不再分析基金、指数、板块或行业主题。

            请改成一个明确的上市公司或股票代码后再问我，例如：
            1. 分析贵州茅台近三年扣非 ROE
            2. 按价值分析流程分析宁德时代
            3. 分析腾讯控股近三年现金及其等价物 / 带息债务
            """.trim();

    private static final String PREPARATION_PROMPT = """
            你是“单只股票财报分析模式”的任务规划器。
            你的职责是判断用户这次是不是要分析一只上市公司的股票，并抽取唯一标的。

            只允许输出一个 JSON 对象，不要输出解释、Markdown、代码块或其他文本。
            JSON 结构如下：
            {
              "analysisScope": "single_stock | multiple_stocks | non_stock | missing_target",
              "target": "唯一分析标的，没有就留空字符串",
              "stockQuery": "给股票或搜索工具使用的简短查询词，没有就留空字符串",
              "mentionedTargets": ["用户明确提到的标的列表"],
              "assetType": "stock | company | fund | index | industry | macro | unknown",
              "reason": "一句话说明判断原因",
              "missingData": ["当前仍缺的重要数据"]
            }

            规则：
            1. 只要用户一次提到了两只及以上股票或上市公司，就判定为 multiple_stocks。
            2. 如果用户问的是基金、ETF、指数、板块、行业、宏观主题，判定为 non_stock。
            3. 如果用户还没有给出明确股票名称或代码，判定为 missing_target。
            4. 不要编造股票代码、竞争对手或财务指标。
            5. missingData 优先写“近三年扣非 ROE”“近三年现金收入比率”“近三年毛利率及同行对比”“近三年存货周转率”“近三年经营性负债”等事实数据，以及“最新 10 年期国债收益率”“当前估值锚”等周行五步法所需证据。
            """;

    private static final String VALUE_ANALYSIS_SYSTEM_PROMPT = """
            你是一名专门做单只股票财报质量分析的价值投资分析师。
            当前模式分两段输出：
            第一段先基于近三年财务数据做财务体检；
            第二段在财务体检之后，继续按“周行五步法”给出跟踪与决策框架。
            不展开基金、指数或多股票并列分析。

            时间处理规则：
            1. 涉及“现在、当前、今天、昨天、明天、本周、本月、本季度、截至目前”等相对时间表达时，不得默认按北京时间或任何固定时区推断。
            2. 如果本轮工具结果里已有“Get current time”，必须基于该结果回答，并写清楚采用的时区。
            3. 如果本轮没有时间工具结果，就明确说明“当前时间/时区尚未校验”，不要自行补出北京时间。

            输出时必须严格使用以下 Markdown 二级标题，顺序也必须保持一致：
            ## 1. 扣非 ROE
            ## 2. 营业收入与现金收入比率
            ## 3. 毛利率与同行对比
            ## 4. 净利润与利润质量
            ## 5. 总资产周转率与杠杆
            ## 6. 现金及其等价物 / 带息债务
            ## 7. 财务健康判断
            ## 8. 周行五步法
            ## 最终投资建议
            ## 还缺什么数据

            指标解释与判断重点：
            1. 扣非 ROE：优先看近三年，最好常年 15% 以上，至少 10%，并说明其是否比普通 ROE 更能反映主业质量。
            2. 现金收入比率：使用“销售商品、提供劳务收到的现金 / 营业收入”，至少大于 0.8，最好大于 1。
            3. 毛利率：先给竞争对手明细，再比较近三年毛利率；竞争对手优先选业务相近且规模接近的上市公司。
            4. 净利润：必须覆盖净利润率、净利润现金含金量（经营活动产生的现金流量净额 / 净利润）、扣非净利润 / 净利润，并说明利润有没有水分。
            5. 总资产周转率与杠杆：必须覆盖总资产周转率、资产负债率、带息债务 / 总资产，并解释权益乘数与经营性负债怎么看。
            6. 现金及其等价物 / 带息债务：优先看近三年，说明偿债冗余度和抗风险能力。
            7. 财务健康判断：必须基于前 6 节给出明确判定，只能选择“合格且健康 / 基本合格但需跟踪 / 暂不合格或存在明显风险”之一，并说明原因。

            周行五步法要求：
            1. 第一步必须提醒用户：只能用闲钱做长线投资，这是所有后续动作的前提。
            2. 第二步必须用一句话抓住公司当前主要矛盾，尽量像投资备忘录标题一样直接。
            3. 第三步必须列“每日/每周跟踪关键指标”：
               - 默认跟踪中国 10 年期国债收益率（无风险收益率）。
               - 如果标的是银行、高股息、公用事业等收益型资产，优先补充股息率、险资/保险资金增减持、机构配置动向。
               - 如果标的不是收益型资产，就把这一项替换成更匹配的行业景气、产品价格、订单、出货量、渠道库存、机构资金流向等，并明确写出替换理由。
            4. 第四步必须判断估值贵还是便宜：
               - 高股息资产优先比较股息率与无风险收益率，通常至少高出约 2 个百分点才算明显便宜。
               - 其他资产可改用 PE、PB、自由现金流收益率、EV/EBIT 等更合适的估值锚，但必须说明为什么选这个锚。
            5. 第五步必须结合市场情绪给出行动框架，说明更适合等待、分批买入、继续持有、减仓卖出，还是等待情绪回落后再回补。
            6. 不要生搬硬套银行案例；银行示例只是方法模板，非银行公司要做口径迁移。

            额外要求：
            1. 只分析一个股票标的；如果信息显示不止一个标的，直接说明无法按当前模式继续。
            2. 财务分析部分优先整理近三年数据，能表格就用 Markdown 表格。
            3. 明确区分“公开财报/公告/搜索结果里的事实”和“你基于事实做的推理”。
            4. 如果某个指标没有直接给出，但工具结果已经有可计算它的原始字段，你必须先自行计算，并在“事实”里写清公式与口径，不能直接判定缺失。
            5. 如果原始字段也不全，优先补查可计算该指标的原始字段，例如 revenue、salesCashReceived、netProfit、operatingCashflow、deductParentNetProfit、totalParentEquity、totalAssets、interestBearingDebtEstimate、inventory、operatingCost 等，再决定是否继续网页搜索。
            6. 找不到数据时才写“暂未查到”或“暂无法确认”，不要编造年份、比率、竞争对手或结论。
            7. 回答语言使用中文，避免空话，先给判断，再给依据。
            """;

    private final ChatMemory chatMemory;
    private final FundAppLocalDocumentRetriever localDocumentRetriever;
    private final FinanceQuestionClassifier financeQuestionClassifier;
    private final ToolCallback[] allTools;
    private final ChatMemoryMetadataService chatMemoryMetadataService;
    private final ChatModelExecutionService chatModelExecutionService;
    private final ChatModelRegistryService chatModelRegistryService;
    private final FinanceConversationGuard financeConversationGuard;

    @Autowired
    public ValueAnalysisService(ChatMemory chatMemory,
                                FundAppLocalDocumentRetriever localDocumentRetriever,
                                FinanceQuestionClassifier financeQuestionClassifier,
                                ToolCallback[] allTools,
                                ChatMemoryMetadataService chatMemoryMetadataService,
                                ChatModelExecutionService chatModelExecutionService,
                                ChatModelRegistryService chatModelRegistryService) {
        this.chatMemory = chatMemory;
        this.localDocumentRetriever = localDocumentRetriever;
        this.financeQuestionClassifier = financeQuestionClassifier;
        this.allTools = allTools;
        this.chatMemoryMetadataService = chatMemoryMetadataService;
        this.chatModelExecutionService = chatModelExecutionService;
        this.chatModelRegistryService = chatModelRegistryService;
        this.financeConversationGuard = new FinanceConversationGuard(
                chatMemory,
                financeQuestionClassifier,
                chatModelExecutionService,
                chatModelRegistryService
        );
    }

    ValueAnalysisService(ChatModel chatModel,
                         ChatMemory chatMemory,
                         FundAppLocalDocumentRetriever localDocumentRetriever,
                         FinanceQuestionClassifier financeQuestionClassifier,
                         ToolCallback[] allTools,
                         ChatMemoryMetadataService chatMemoryMetadataService) {
        this(
                chatMemory,
                localDocumentRetriever,
                financeQuestionClassifier,
                allTools,
                chatMemoryMetadataService,
                new ChatModelExecutionService(),
                createCompatibilityChatModelRegistry(chatModel)
        );
    }

    public String analyze(String message, String chatId) {
        return analyze(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public String analyze(String message, String chatId, String advisorStyle) {
        return analyze(message, chatId, advisorStyle, null);
    }

    public String analyze(String message, String chatId, String advisorStyle, String modelId) {
        AdvisorStyle style = AdvisorStyle.resolve(advisorStyle);
        PreparedValueAnalysis prepared = prepareAnalysis(message, chatId, modelId);
        if (prepared.directReply() != null) {
            if (prepared.shouldRecordDirectReply()) {
                recordDirectReply(chatId, message, prepared.directReply(), prepared.trace());
            }
            return prepared.directReply();
        }

        String content = chatModelExecutionService.executeWithRetry("价值分析", () -> createValueAnalysisChatClient(modelId)
                .prompt()
                .system(buildSystemPrompt(style))
                .user(prepared.finalPrompt())
                .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .call()
                .content());
        log.info("value analysis scope={}, target={}", prepared.preparation().scope(), prepared.preparation().target());
        log.info("value analysis content: {}", content);
        return finalizeReply(chatId, message, content, prepared.trace());
    }

    public Flux<String> analyzeStream(String message, String chatId) {
        return analyzeStream(message, chatId, AdvisorStyle.DEFAULT.code());
    }

    public Flux<String> analyzeStream(String message, String chatId, String advisorStyle) {
        return analyzeStream(message, chatId, advisorStyle, null);
    }

    public Flux<String> analyzeStream(String message, String chatId, String advisorStyle, String modelId) {
        AdvisorStyle style = AdvisorStyle.resolve(advisorStyle);
        return Flux.defer(() -> {
            PreparedValueAnalysis prepared = prepareAnalysis(message, chatId, modelId);
            if (prepared.directReply() != null) {
                if (prepared.shouldRecordDirectReply()) {
                    recordDirectReply(chatId, message, prepared.directReply(), prepared.trace());
                }
                return Flux.just(prepared.directReply());
            }

            return chatModelExecutionService.streamWithRetryAndFallback(
                            "价值分析流式对话",
                            () -> createValueAnalysisChatClient(modelId)
                                    .prompt()
                                    .system(buildSystemPrompt(style))
                                    .user(prepared.finalPrompt())
                                    .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                                    .stream()
                                    .content(),
                            () -> createValueAnalysisChatClient(modelId)
                                    .prompt()
                                    .system(buildSystemPrompt(style))
                                    .user(prepared.finalPrompt())
                                    .advisors(spec -> spec.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                                    .call()
                                    .content()
                    )
                    .transform(stream -> finalizeStreamReply(stream, chatId, message, prepared.trace()));
        });
    }

    private String buildSystemPrompt(AdvisorStyle style) {
        List<String> promptSections = new ArrayList<>();
        promptSections.add(VALUE_ANALYSIS_SYSTEM_PROMPT.trim());
        if (style != null && style.hasSystemPrompt()) {
            promptSections.add(style.systemPrompt());
        }
        return String.join("\n\n", promptSections);
    }

    private PreparedValueAnalysis prepareAnalysis(String message, String chatId, String modelId) {
        if (!financeConversationGuard.isFinanceRelatedQuestion(chatId, message, modelId)) {
            return new PreparedValueAnalysis(null, null, VALUE_MODE_OUT_OF_DOMAIN_REPLY, null, false);
        }

        ValueAnalysisPreparation heuristicPreparation = buildHeuristicPreparation(message);
        if (heuristicPreparation.scope() == AnalysisScope.NON_STOCK) {
            return new PreparedValueAnalysis(heuristicPreparation, null, VALUE_MODE_NON_STOCK_REPLY, null, true);
        }
        if (heuristicPreparation.scope() == AnalysisScope.MULTIPLE_STOCKS) {
            return new PreparedValueAnalysis(heuristicPreparation, null, buildMultipleTargetsReply(heuristicPreparation), null, true);
        }

        ValueAnalysisPreparation preparation = preparePreparation(message, heuristicPreparation, modelId);
        if (preparation.scope() == AnalysisScope.NON_STOCK) {
            return new PreparedValueAnalysis(preparation, null, VALUE_MODE_NON_STOCK_REPLY, null, true);
        }
        if (preparation.scope() == AnalysisScope.MULTIPLE_STOCKS) {
            return new PreparedValueAnalysis(preparation, null, buildMultipleTargetsReply(preparation), null, true);
        }
        if (preparation.scope() == AnalysisScope.MISSING_TARGET
                || (!StringUtils.hasText(preparation.target()) && !StringUtils.hasText(preparation.stockQuery()))) {
            return new PreparedValueAnalysis(preparation, null, buildMissingTargetReply(preparation), null, true);
        }

        RetrievedRagContext ragContext = retrieveRagContext(message, preparation);
        CollectedRealtimeEvidence evidence = collectRealtimeEvidence(preparation, message);
        ValueAnalysisPreparation evidenceAwarePreparation = withMissingData(preparation, evidence.remainingMissingData());
        String finalPrompt = buildFinalPrompt(message, evidenceAwarePreparation, ragContext.context(), evidence.results());
        ChatTrace trace = buildTrace(evidence.results(), ragContext.documents());
        return new PreparedValueAnalysis(evidenceAwarePreparation, finalPrompt, null, trace, true);
    }

    private ValueAnalysisPreparation preparePreparation(String message,
                                                       ValueAnalysisPreparation heuristicPreparation,
                                                       String modelId) {
        try {
            String response = chatModelExecutionService.executeWithRetry("价值分析前置识别", () -> createPreparationClient(modelId)
                    .prompt()
                    .system(PREPARATION_PROMPT)
                    .user(message)
                    .call()
                    .content(), false);
            ValueAnalysisPreparation parsed = parsePreparation(response);
            return mergePreparations(heuristicPreparation, sanitizePreparation(parsed, message));
        } catch (Exception ex) {
            log.warn("Failed to prepare value analysis for message={}. Falling back to heuristic preparation.", message);
            log.debug("Value analysis preparation failure for message={}", message, ex);
            return heuristicPreparation;
        }
    }

    private ValueAnalysisPreparation parsePreparation(String response) {
        String json = extractFirstJsonObject(response);
        if (!StringUtils.hasText(json)) {
            return null;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            return new ValueAnalysisPreparation(
                    resolveScope(root.path("analysisScope").asText("")),
                    root.path("target").asText(""),
                    root.path("stockQuery").asText(""),
                    readStringList(root.path("mentionedTargets")),
                    root.path("assetType").asText("unknown"),
                    root.path("reason").asText(""),
                    readStringList(root.path("missingData"))
            );
        } catch (Exception ex) {
            log.warn("Failed to parse value analysis preparation response: {}", response, ex);
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item == null ? "" : item.asText("");
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private ValueAnalysisPreparation sanitizePreparation(ValueAnalysisPreparation preparation, String message) {
        if (preparation == null) {
            return buildHeuristicPreparation(message);
        }

        List<String> mentionedTargets = uniqueNonBlank(preparation.mentionedTargets());
        String target = cleanupValue(firstNonBlank(preparation.target(), preparation.stockQuery()));
        String stockQuery = cleanupValue(firstNonBlank(preparation.stockQuery(), target));
        AnalysisScope scope = normalizeScope(preparation.scope(), target, mentionedTargets, message);
        List<String> missingData = mergeMissingData(preparation.missingData(), defaultMissingData());

        return new ValueAnalysisPreparation(
                scope,
                target,
                stockQuery,
                mentionedTargets,
                cleanupValue(preparation.assetType()),
                cleanupValue(preparation.reason()),
                missingData
        );
    }

    private ValueAnalysisPreparation mergePreparations(ValueAnalysisPreparation heuristicPreparation,
                                                       ValueAnalysisPreparation preparedByModel) {
        if (preparedByModel == null) {
            return heuristicPreparation;
        }

        List<String> mentionedTargets = uniqueNonBlank(mergeLists(
                heuristicPreparation.mentionedTargets(),
                preparedByModel.mentionedTargets()
        ));
        String target = cleanupValue(firstNonBlank(preparedByModel.target(), heuristicPreparation.target()));
        String stockQuery = cleanupValue(firstNonBlank(preparedByModel.stockQuery(), heuristicPreparation.stockQuery(), target));
        AnalysisScope scope = preferredScope(heuristicPreparation.scope(), preparedByModel.scope(), target, mentionedTargets);

        return new ValueAnalysisPreparation(
                scope,
                target,
                stockQuery,
                mentionedTargets,
                cleanupValue(firstNonBlank(preparedByModel.assetType(), heuristicPreparation.assetType())),
                cleanupValue(firstNonBlank(preparedByModel.reason(), heuristicPreparation.reason())),
                mergeMissingData(heuristicPreparation.missingData(), preparedByModel.missingData())
        );
    }

    private ValueAnalysisPreparation buildHeuristicPreparation(String message) {
        List<String> explicitIdentifiers = extractExplicitStockIdentifiers(message);
        if (explicitIdentifiers.size() > 1) {
            return new ValueAnalysisPreparation(
                    AnalysisScope.MULTIPLE_STOCKS,
                    "",
                    "",
                    explicitIdentifiers,
                    "stock",
                    "检测到多个股票代码或股票标识",
                    defaultMissingData()
            );
        }

        List<String> multipleTargets = detectMultipleTargets(message);
        if (multipleTargets.size() > 1) {
            return new ValueAnalysisPreparation(
                    AnalysisScope.MULTIPLE_STOCKS,
                    "",
                    "",
                    multipleTargets,
                    "stock",
                    "检测到多个股票或上市公司名称",
                    defaultMissingData()
            );
        }

        if (looksLikeNonStockTopic(message)) {
            return new ValueAnalysisPreparation(
                    AnalysisScope.NON_STOCK,
                    "",
                    "",
                    List.of(),
                    "non_stock",
                    "问题更像基金、指数、行业或主题分析",
                    defaultMissingData()
            );
        }

        String targetCandidate = firstNonBlank(
                explicitIdentifiers.isEmpty() ? "" : explicitIdentifiers.get(0),
                extractTargetCandidate(message)
        );

        AnalysisScope scope = StringUtils.hasText(targetCandidate) ? AnalysisScope.SINGLE_STOCK : AnalysisScope.MISSING_TARGET;
        return new ValueAnalysisPreparation(
                scope,
                cleanupValue(targetCandidate),
                cleanupValue(targetCandidate),
                StringUtils.hasText(targetCandidate) ? List.of(targetCandidate.trim()) : List.of(),
                "stock",
                scope == AnalysisScope.SINGLE_STOCK ? "启发式识别到单只股票标的" : "还缺明确股票标的",
                defaultMissingData()
        );
    }

    private AnalysisScope preferredScope(AnalysisScope heuristicScope,
                                         AnalysisScope preparedScope,
                                         String target,
                                         List<String> mentionedTargets) {
        if (heuristicScope == AnalysisScope.MULTIPLE_STOCKS || heuristicScope == AnalysisScope.NON_STOCK) {
            return heuristicScope;
        }
        if (preparedScope == AnalysisScope.MULTIPLE_STOCKS || preparedScope == AnalysisScope.NON_STOCK) {
            return preparedScope;
        }
        return normalizeScope(preparedScope, target, mentionedTargets, "");
    }

    private AnalysisScope normalizeScope(AnalysisScope scope, String target, List<String> mentionedTargets, String message) {
        if (scope == AnalysisScope.MULTIPLE_STOCKS || scope == AnalysisScope.NON_STOCK) {
            return scope;
        }
        if (mentionedTargets != null && mentionedTargets.size() > 1) {
            return AnalysisScope.MULTIPLE_STOCKS;
        }
        if (looksLikeNonStockTopic(message) && !StringUtils.hasText(target)) {
            return AnalysisScope.NON_STOCK;
        }
        return StringUtils.hasText(target) ? AnalysisScope.SINGLE_STOCK : AnalysisScope.MISSING_TARGET;
    }

    private AnalysisScope resolveScope(String rawScope) {
        if (!StringUtils.hasText(rawScope)) {
            return AnalysisScope.MISSING_TARGET;
        }
        return switch (rawScope.trim().toLowerCase(Locale.ROOT)) {
            case "single_stock" -> AnalysisScope.SINGLE_STOCK;
            case "multiple_stocks" -> AnalysisScope.MULTIPLE_STOCKS;
            case "non_stock" -> AnalysisScope.NON_STOCK;
            default -> AnalysisScope.MISSING_TARGET;
        };
    }

    private String buildMultipleTargetsReply(ValueAnalysisPreparation preparation) {
        String detectedTargets = preparation == null || preparation.mentionedTargets().isEmpty()
                ? "- 暂未稳定识别出具体名称，但已判断为多个标的"
                : preparation.mentionedTargets().stream()
                .map(target -> "- " + target)
                .collect(Collectors.joining("\n"));

        return """
                当前价值分析模式已经改成“单只股票财报分析”，每次只分析一只股票。

                这次识别到的多个标的：
                %s

                请删到只剩一个上市公司或股票代码后再问我，例如：
                1. 分析贵州茅台近三年扣非 ROE 和毛利率
                2. 按价值分析流程分析五粮液
                3. 分析腾讯控股近三年现金及其等价物 / 带息债务
                """.formatted(detectedTargets);
    }

    private String buildMissingTargetReply(ValueAnalysisPreparation preparation) {
        String missingData = formatList(preparation == null ? defaultMissingData() : preparation.missingData());
        return """
                当前价值分析模式只支持单只股票或单家上市公司，但这次还缺一个明确标的。

                请至少补充以下任一信息后再问我一次：
                1. 上市公司名称，例如“贵州茅台”或“腾讯控股”
                2. 股票代码，例如“600519”“00700”“AAPL”
                3. 你最关心的财报维度，例如“扣非 ROE”“毛利率”“现金及其等价物 / 带息债务”

                当前优先想补齐的数据：
                %s
                """.formatted(missingData);
    }

    private RetrievedRagContext retrieveRagContext(String message, ValueAnalysisPreparation preparation) {
        String ragQuery = String.join(" ",
                firstNonBlank(preparation.target(), preparation.stockQuery(), message),
                "价值投资 财报 扣非ROE 现金收入比率 毛利率 净利润现金含金量 总资产周转率 资产负债率 带息债务");
        List<FundAppLocalDocumentRetriever.RetrievedDocument> documents = localDocumentRetriever.search(ragQuery, MAX_RAG_REFERENCES);
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

    private CollectedRealtimeEvidence collectRealtimeEvidence(ValueAnalysisPreparation preparation, String message) {
        List<ToolExecutionResult> allResults = new ArrayList<>();
        Set<String> requiredGapKeys = deriveGapKeys(preparation, message);
        Set<String> executedSignatures = new LinkedHashSet<>();
        int remainingCallBudget = TOOL_LOOP_LIMIT.maxTotalCalls();
        List<PlannedToolCall> plannedCalls = buildInitialEvidenceCalls(preparation, requiredGapKeys, message);
        Set<String> satisfiedGapKeys = new LinkedHashSet<>();

        for (int round = 1; round <= TOOL_LOOP_LIMIT.maxRounds(); round++) {
            if (plannedCalls.isEmpty() || remainingCallBudget <= 0) {
                break;
            }

            List<PlannedToolCall> roundCalls = selectNewEvidenceCalls(plannedCalls, executedSignatures, remainingCallBudget);
            if (roundCalls.isEmpty()) {
                break;
            }

            List<ToolExecutionResult> roundResults = executePlannedToolCalls(roundCalls);
            allResults.addAll(roundResults);
            remainingCallBudget -= roundResults.size();

            for (PlannedToolCall call : roundCalls) {
                executedSignatures.add(buildToolCallSignature(call.toolName(), call.argumentsJson()));
            }

            satisfiedGapKeys = evaluateSatisfiedGapKeys(requiredGapKeys, allResults);
            Set<String> remainingGapKeys = new LinkedHashSet<>(requiredGapKeys);
            remainingGapKeys.removeAll(satisfiedGapKeys);
            if (remainingGapKeys.isEmpty()) {
                break;
            }

            plannedCalls = buildContinuationEvidenceCalls(preparation, message, remainingGapKeys, round + 1);
        }

        List<String> remainingMissingData = mapGapKeysToLabels(requiredGapKeys, satisfiedGapKeys);
        log.info("value analysis tool results: {}", allResults);
        log.info("value analysis remaining gaps: {}", remainingMissingData);
        return new CollectedRealtimeEvidence(allResults, remainingMissingData);
    }

    private List<ToolExecutionResult> collectRealtimeEvidence(ValueAnalysisPreparation preparation) {
        if (preparation == null) {
            return List.of();
        }
        return collectRealtimeEvidence(preparation, firstNonBlank(preparation.stockQuery(), preparation.target())).results();
    }

    private List<SearchTask> buildSearchTasks(ValueAnalysisPreparation preparation) {
        String target = firstNonBlank(preparation.target(), preparation.stockQuery());
        if (!StringUtils.hasText(target)) {
            return List.of();
        }

        return List.of(
                new SearchTask(
                        "同行与可比公司",
                        target + " 竞争对手 可比公司 同行业 规模接近 毛利率 市占率",
                        List.of(GapKey.GROSS_MARGIN_PEERS.code())
                ),
                new SearchTask(
                        "近期公告与经营变化",
                        target + " 最新 公告 年报 经营变化 风险提示",
                        List.of()
                ),
                new SearchTask(
                        "10 年期国债收益率",
                        "中国 10年期国债收益率 最新",
                        List.of(GapKey.RISK_FREE_RATE.code())
                ),
                new SearchTask(
                        "机构增减持与险资动向",
                        target + " 机构增持 减持 险资 保险 持仓 最新",
                        List.of(GapKey.CAPITAL_FLOW_SIGNAL.code())
                ),
                new SearchTask(
                        "估值与股息率",
                        target + " 股息率 分红收益率 市盈率 PE PB 估值 最新",
                        List.of(GapKey.VALUATION_MARKER.code())
                )
        ).stream().limit(MAX_SEARCH_CALLS).toList();
    }

    private List<PlannedToolCall> buildInitialEvidenceCalls(ValueAnalysisPreparation preparation,
                                                            Set<String> requiredGapKeys,
                                                            String message) {
        String stockQuery = firstNonBlank(preparation.stockQuery(), preparation.target());
        if (!StringUtils.hasText(stockQuery)) {
            return List.of();
        }

        List<PlannedToolCall> calls = new ArrayList<>();
        if (needsCurrentTimeEvidence(message) && findToolCallback("Get current time") != null) {
            calls.add(new PlannedToolCall(
                    "当前时间校验",
                    "Get current time",
                    linkedArguments("timezoneHint", message),
                    List.of()
            ));
        }
        calls.add(new PlannedToolCall(
                "标的确认",
                "Get realtime stock quote",
                linkedArguments("stockQuery", stockQuery),
                List.of()
        ));
        calls.add(new PlannedToolCall(
                "近三年核心财报",
                "Search A-share financial reports",
                financialReportArguments(stockQuery, FINANCIAL_ANALYSIS_YEAR_LIMIT, "annual", "core"),
                List.of(
                        GapKey.DEDUCT_ROE.code(),
                        GapKey.CASH_SALES_RATIO.code(),
                        GapKey.PROFIT_QUALITY.code(),
                        GapKey.LEVERAGE_DEBT.code(),
                        GapKey.CASH_DEBT.code(),
                        GapKey.INVENTORY_TURNOVER.code(),
                        GapKey.OPERATING_LIABILITIES.code()
                )
        ));

        if (needsSearchEvidence(requiredGapKeys)) {
            for (SearchTask task : buildSearchTasks(preparation)) {
                calls.add(new PlannedToolCall(
                        task.focus(),
                        "Search for information",
                        linkedArguments("query", task.query()),
                        task.coveredGapKeys()
                ));
            }
        }

        return calls;
    }

    private List<PlannedToolCall> buildContinuationEvidenceCalls(ValueAnalysisPreparation preparation,
                                                                 String message,
                                                                 Set<String> remainingGapKeys,
                                                                 int nextRound) {
        if (remainingGapKeys.isEmpty() || nextRound > TOOL_LOOP_LIMIT.maxRounds()) {
            return List.of();
        }

        String stockQuery = firstNonBlank(preparation.stockQuery(), preparation.target(), message);
        if (!StringUtils.hasText(stockQuery)) {
            return List.of();
        }

        List<PlannedToolCall> calls = new ArrayList<>();
        if (nextRound == 2) {
            if (requiresIncomeFocus(remainingGapKeys)) {
                calls.add(new PlannedToolCall(
                        "利润表与收入质量补查",
                        "Search A-share financial reports",
                        financialReportArguments(stockQuery, FINANCIAL_ANALYSIS_YEAR_LIMIT, "annual", "income"),
                        List.of(
                                GapKey.DEDUCT_ROE.code(),
                                GapKey.CASH_SALES_RATIO.code(),
                                GapKey.PROFIT_QUALITY.code()
                        )
                ));
            }
            if (requiresBalanceFocus(remainingGapKeys)) {
                calls.add(new PlannedToolCall(
                        "资产负债表、归母权益与资产质量补查",
                        "Search A-share financial reports",
                        financialReportArguments(stockQuery, FINANCIAL_ANALYSIS_YEAR_LIMIT, "annual", "balance"),
                        List.of(
                                GapKey.DEDUCT_ROE.code(),
                                GapKey.LEVERAGE_DEBT.code(),
                                GapKey.CASH_DEBT.code(),
                                GapKey.INVENTORY_TURNOVER.code(),
                                GapKey.OPERATING_LIABILITIES.code()
                        )
                ));
            }
            if (requiresCashflowFocus(remainingGapKeys)) {
                calls.add(new PlannedToolCall(
                        "现金流与利润含金量补查",
                        "Search A-share financial reports",
                        financialReportArguments(stockQuery, FINANCIAL_ANALYSIS_YEAR_LIMIT, "annual", "cashflow"),
                    List.of(
                            GapKey.CASH_SALES_RATIO.code(),
                            GapKey.PROFIT_QUALITY.code(),
                            GapKey.CASH_DEBT.code()
                    )
                ));
            }
            if (!calls.isEmpty()) {
                return calls;
            }
        }

        if (remainingGapKeys.contains(GapKey.DEDUCT_ROE.code())) {
            calls.add(new PlannedToolCall(
                    "近三年扣非 ROE 原始字段补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " " + FINANCIAL_ANALYSIS_QUERY_WINDOW + " 扣非ROE 扣非净资产收益率 扣非净利润 归母净资产 ROEKCJQ"),
                    List.of(GapKey.DEDUCT_ROE.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.GROSS_MARGIN_PEERS.code())) {
            calls.add(new PlannedToolCall(
                    "同行毛利率与可比公司补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 可比公司 竞争对手 " + FINANCIAL_ANALYSIS_QUERY_WINDOW + " 毛利率 主营业务"),
                    List.of(GapKey.GROSS_MARGIN_PEERS.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.CASH_SALES_RATIO.code())) {
            calls.add(new PlannedToolCall(
                    "现金收入比率原始字段补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 销售商品 提供劳务收到的现金 营业收入 " + FINANCIAL_ANALYSIS_QUERY_WINDOW),
                    List.of(GapKey.CASH_SALES_RATIO.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.PROFIT_QUALITY.code())) {
            calls.add(new PlannedToolCall(
                    "利润质量原始字段补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 净利润 经营活动产生的现金流量净额 扣非净利润 净利润现金含金量 " + FINANCIAL_ANALYSIS_QUERY_WINDOW),
                    List.of(GapKey.PROFIT_QUALITY.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.LEVERAGE_DEBT.code())) {
            calls.add(new PlannedToolCall(
                    "杠杆与带息债务原始字段补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 资产负债率 带息债务 总资产 " + FINANCIAL_ANALYSIS_QUERY_WINDOW),
                    List.of(GapKey.LEVERAGE_DEBT.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.CASH_DEBT.code())) {
            calls.add(new PlannedToolCall(
                    "现金与债务原始字段补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 现金及其等价物 带息债务 现金债务比 " + FINANCIAL_ANALYSIS_QUERY_WINDOW),
                    List.of(GapKey.CASH_DEBT.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.INVENTORY_TURNOVER.code())
                || remainingGapKeys.contains(GapKey.OPERATING_LIABILITIES.code())) {
            calls.add(new PlannedToolCall(
                    "存货周转与经营性负债原始字段补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 营业成本 存货 存货周转率 存货周转天数 应付账款 合同负债 应付票据 应付职工薪酬 应交税费 经营性负债 " + FINANCIAL_ANALYSIS_QUERY_WINDOW),
                    List.of(GapKey.INVENTORY_TURNOVER.code(), GapKey.OPERATING_LIABILITIES.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.RISK_FREE_RATE.code())) {
            calls.add(new PlannedToolCall(
                    "无风险收益率补查",
                    "Search for information",
                    linkedArguments("query", "中国 10年期国债收益率 最新"),
                    List.of(GapKey.RISK_FREE_RATE.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.CAPITAL_FLOW_SIGNAL.code())) {
            calls.add(new PlannedToolCall(
                    "机构增减持与险资动向补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 机构增持 减持 险资 保险 持仓 最新"),
                    List.of(GapKey.CAPITAL_FLOW_SIGNAL.code())
            ));
        }
        if (remainingGapKeys.contains(GapKey.VALUATION_MARKER.code())) {
            calls.add(new PlannedToolCall(
                    "估值锚补查",
                    "Search for information",
                    linkedArguments("query", stockQuery + " 股息率 分红收益率 市盈率 PE PB 估值 最新"),
                    List.of(GapKey.VALUATION_MARKER.code())
            ));
        }

        return calls.stream().limit(MAX_SEARCH_CALLS).toList();
    }

    private List<PlannedToolCall> selectNewEvidenceCalls(List<PlannedToolCall> plannedCalls,
                                                         Set<String> executedSignatures,
                                                         int remainingCallBudget) {
        if (plannedCalls == null || plannedCalls.isEmpty() || remainingCallBudget <= 0) {
            return List.of();
        }

        List<PlannedToolCall> selectedCalls = new ArrayList<>();
        Set<String> roundSignatures = new LinkedHashSet<>();
        int roundLimit = resolveRoundToolCallLimit(remainingCallBudget);

        for (PlannedToolCall plannedCall : plannedCalls) {
            if (plannedCall == null || !StringUtils.hasText(plannedCall.toolName())) {
                continue;
            }

            String signature = buildToolCallSignature(plannedCall.toolName(), plannedCall.argumentsJson());
            if (executedSignatures.contains(signature) || !roundSignatures.add(signature)) {
                continue;
            }

            selectedCalls.add(plannedCall);
            if (selectedCalls.size() >= roundLimit) {
                break;
            }
        }

        return selectedCalls;
    }

    private int resolveRoundToolCallLimit(int remainingCallBudget) {
        if (remainingCallBudget <= 0) {
            return 0;
        }
        if (TOOL_LOOP_LIMIT.maxCallsPerRound() <= 0) {
            return remainingCallBudget;
        }
        return Math.max(0, Math.min(remainingCallBudget, TOOL_LOOP_LIMIT.maxCallsPerRound()));
    }

    private List<ToolExecutionResult> executePlannedToolCalls(List<PlannedToolCall> plannedCalls) {
        List<ToolExecutionResult> results = new ArrayList<>();
        for (PlannedToolCall plannedCall : plannedCalls) {
            results.add(executeTool(
                    plannedCall.focus(),
                    plannedCall.toolName(),
                    plannedCall.arguments(),
                    plannedCall.coveredGapKeys()
            ));
        }
        return results;
    }

    private boolean requiresIncomeFocus(Set<String> remainingGapKeys) {
        return remainingGapKeys.contains(GapKey.DEDUCT_ROE.code())
                || remainingGapKeys.contains(GapKey.CASH_SALES_RATIO.code())
                || remainingGapKeys.contains(GapKey.PROFIT_QUALITY.code());
    }

    private boolean requiresBalanceFocus(Set<String> remainingGapKeys) {
        return remainingGapKeys.contains(GapKey.DEDUCT_ROE.code())
                || remainingGapKeys.contains(GapKey.LEVERAGE_DEBT.code())
                || remainingGapKeys.contains(GapKey.CASH_DEBT.code())
                || remainingGapKeys.contains(GapKey.INVENTORY_TURNOVER.code())
                || remainingGapKeys.contains(GapKey.OPERATING_LIABILITIES.code());
    }

    private boolean requiresCashflowFocus(Set<String> remainingGapKeys) {
        return remainingGapKeys.contains(GapKey.CASH_SALES_RATIO.code())
                || remainingGapKeys.contains(GapKey.PROFIT_QUALITY.code())
                || remainingGapKeys.contains(GapKey.CASH_DEBT.code());
    }

    private boolean needsSearchEvidence(Set<String> requiredGapKeys) {
        return requiredGapKeys.contains(GapKey.GROSS_MARGIN_PEERS.code())
                || requiredGapKeys.contains(GapKey.RISK_FREE_RATE.code())
                || requiredGapKeys.contains(GapKey.CAPITAL_FLOW_SIGNAL.code())
                || requiredGapKeys.contains(GapKey.VALUATION_MARKER.code());
    }

    private Set<String> deriveGapKeys(ValueAnalysisPreparation preparation, String message) {
        Set<String> gapKeys = new LinkedHashSet<>();
        List<String> sourceItems = mergeMissingData(
                preparation == null ? List.of() : preparation.missingData(),
                inferGapLabelsFromMessage(message)
        );
        for (String item : sourceItems) {
            String normalized = String.valueOf(item).toLowerCase(Locale.ROOT);
            if (normalized.contains("扣非") && normalized.contains("roe")) {
                gapKeys.add(GapKey.DEDUCT_ROE.code());
            }
            if (normalized.contains("现金收入")
                    || normalized.contains("销售商品")
                    || normalized.contains("劳务收到的现金")
                    || normalized.contains("现金收入比率")) {
                gapKeys.add(GapKey.CASH_SALES_RATIO.code());
            }
            if (normalized.contains("毛利率") || normalized.contains("同行") || normalized.contains("可比公司")) {
                gapKeys.add(GapKey.GROSS_MARGIN_PEERS.code());
            }
            if (normalized.contains("净利润")
                    || normalized.contains("利润质量")
                    || normalized.contains("现金含金量")
                    || normalized.contains("扣非净利润")) {
                gapKeys.add(GapKey.PROFIT_QUALITY.code());
            }
            if (normalized.contains("总资产周转率")
                    || normalized.contains("资产负债率")
                    || normalized.contains("带息债务")
                    || normalized.contains("杠杆")) {
                gapKeys.add(GapKey.LEVERAGE_DEBT.code());
            }
            if (normalized.contains("现金及其等价物")
                    || normalized.contains("现金")
                    || normalized.contains("偿债")
                    || normalized.contains("债务")) {
                gapKeys.add(GapKey.CASH_DEBT.code());
            }
            if (normalized.contains("存货周转")) {
                gapKeys.add(GapKey.INVENTORY_TURNOVER.code());
            }
            if (normalized.contains("经营性负债")) {
                gapKeys.add(GapKey.OPERATING_LIABILITIES.code());
            }
            if (normalized.contains("国债收益率") || normalized.contains("无风险收益率")) {
                gapKeys.add(GapKey.RISK_FREE_RATE.code());
            }
            if (normalized.contains("险资") || normalized.contains("保险资金")
                    || normalized.contains("机构增持") || normalized.contains("增减持")) {
                gapKeys.add(GapKey.CAPITAL_FLOW_SIGNAL.code());
            }
            if (normalized.contains("股息率") || normalized.contains("估值锚")
                    || normalized.contains("市盈率") || normalized.contains("pe") || normalized.contains("pb")) {
                gapKeys.add(GapKey.VALUATION_MARKER.code());
            }
        }

        if (gapKeys.isEmpty()) {
            gapKeys.addAll(List.of(
                    GapKey.DEDUCT_ROE.code(),
                    GapKey.CASH_SALES_RATIO.code(),
                    GapKey.GROSS_MARGIN_PEERS.code(),
                    GapKey.PROFIT_QUALITY.code(),
                    GapKey.LEVERAGE_DEBT.code(),
                    GapKey.CASH_DEBT.code(),
                    GapKey.INVENTORY_TURNOVER.code(),
                    GapKey.OPERATING_LIABILITIES.code(),
                    GapKey.RISK_FREE_RATE.code(),
                    GapKey.CAPITAL_FLOW_SIGNAL.code(),
                    GapKey.VALUATION_MARKER.code()
            ));
        }
        return gapKeys;
    }

    private List<String> inferGapLabelsFromMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return List.of();
        }

        List<String> inferredLabels = new ArrayList<>();
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("存货周转")) {
            inferredLabels.add("近三年存货周转率与存货周转天数");
        }
        if (normalized.contains("经营性负债")) {
            inferredLabels.add("近三年经营性负债及结构");
        }
        if (normalized.contains("国债收益率") || normalized.contains("无风险收益率")) {
            inferredLabels.add("最新 10 年期国债收益率（无风险收益率）");
        }
        if (normalized.contains("增持") || normalized.contains("减持")
                || normalized.contains("险资") || normalized.contains("保险")) {
            inferredLabels.add("近期机构增减持 / 险资动向");
        }
        if (normalized.contains("股息率") || normalized.contains("估值")
                || normalized.contains("pe") || normalized.contains("pb")) {
            inferredLabels.add("当前估值锚（股息率 / PE / PB 等）");
        }
        return inferredLabels;
    }

    private boolean needsCurrentTimeEvidence(String message) {
        String normalized = String.valueOf(message).toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "当前时间", "现在几点", "几点了", "当前日期", "今天几号", "今天星期几", "今天周几", "当地时间", "时区",
                "current time", "what time", "today date", "timezone")
                || containsAny(normalized,
                "今天", "昨天", "明天", "本周", "上周", "本月", "上月", "本季度", "上季度", "今年", "去年",
                "截至今天", "截至昨日", "截至目前", "截至现在");
    }

    private List<String> mapGapKeysToLabels(Set<String> requiredGapKeys, Set<String> satisfiedGapKeys) {
        Set<String> remainingGapKeys = new LinkedHashSet<>(requiredGapKeys);
        remainingGapKeys.removeAll(satisfiedGapKeys);

        List<String> labels = new ArrayList<>();
        for (String gapKey : remainingGapKeys) {
            GapKey resolved = GapKey.fromCode(gapKey);
            if (resolved != null) {
                labels.add(resolved.label());
            }
        }
        return labels;
    }

    private Set<String> evaluateSatisfiedGapKeys(Set<String> requiredGapKeys, List<ToolExecutionResult> toolResults) {
        Set<String> satisfiedGapKeys = new LinkedHashSet<>();
        if (toolResults == null || toolResults.isEmpty()) {
            return satisfiedGapKeys;
        }

        boolean deductRoeReady = false;
        boolean cashSalesRatioReady = false;
        boolean revenueReady = false;
        boolean salesCashReady = false;
        boolean operatingCostReady = false;
        boolean grossMarginReady = false;
        boolean peersReady = false;
        boolean netMarginReady = false;
        boolean operatingCashflowReady = false;
        boolean netProfitReady = false;
        boolean cashProfitRatioReady = false;
        boolean deductProfitRatioReady = false;
        boolean deductProfitReady = false;
        boolean parentNetProfitReady = false;
        boolean assetTurnoverReady = false;
        boolean debtAssetReady = false;
        boolean totalAssetsReady = false;
        boolean interestDebtAssetReady = false;
        boolean cashReady = false;
        boolean interestDebtReady = false;
        boolean cashDebtRatioReady = false;
        boolean inventoryTurnoverReady = false;
        boolean inventoryBalanceReady = false;
        boolean operatingLiabilityReady = false;
        boolean parentEquityReady = false;
        boolean riskFreeRateReady = false;
        boolean capitalFlowReady = false;
        boolean valuationMarkerReady = false;

        for (ToolExecutionResult result : toolResults) {
            if (!isSuccessfulToolResult(result) || !StringUtils.hasText(result.toolName())) {
                continue;
            }

            String normalizedToolName = normalizeToolName(result.toolName());
            if (normalizeToolName("Search A-share financial reports").equals(normalizedToolName)) {
                deductRoeReady = deductRoeReady || reportsContainField(
                        result.output(),
                        "deductRoeWeighted",
                        "deductRoeEstimate"
                );
                revenueReady = revenueReady || reportsContainField(result.output(), "revenue");
                operatingCostReady = operatingCostReady || reportsContainField(result.output(), "operatingCost");
                salesCashReady = salesCashReady || reportsContainField(result.output(), "salesCashReceived");
                cashSalesRatioReady = cashSalesRatioReady || reportsContainField(result.output(), "cashSalesRatio");
                grossMarginReady = grossMarginReady || reportsContainField(result.output(), "grossMargin");
                netMarginReady = netMarginReady || reportsContainField(result.output(), "netMargin");
                operatingCashflowReady = operatingCashflowReady || reportsContainField(
                        result.output(),
                        "operatingCashflow",
                        "operatingNetCashflow"
                );
                netProfitReady = netProfitReady || reportsContainField(result.output(), "netProfit");
                cashProfitRatioReady = cashProfitRatioReady || reportsContainField(
                        result.output(),
                        "operatingCashflowToNetProfit"
                );
                deductProfitReady = deductProfitReady || reportsContainField(
                        result.output(),
                        "deductParentNetProfit"
                );
                parentNetProfitReady = parentNetProfitReady || reportsContainField(result.output(), "parentNetProfit");
                deductProfitRatioReady = deductProfitRatioReady || reportsContainField(
                        result.output(),
                        "deductParentNetProfitRatio"
                );
                assetTurnoverReady = assetTurnoverReady || reportsContainField(result.output(), "totalAssetTurnover");
                debtAssetReady = debtAssetReady || reportsContainField(result.output(), "debtAssetRatio");
                totalAssetsReady = totalAssetsReady || reportsContainField(result.output(), "totalAssets");
                interestDebtAssetReady = interestDebtAssetReady || reportsContainField(
                        result.output(),
                        "interestBearingDebtToAsset"
                );
                cashReady = cashReady || reportsContainField(result.output(), "cashAndEquivalentsEnd", "monetaryFunds");
                interestDebtReady = interestDebtReady || reportsContainField(result.output(), "interestBearingDebtEstimate");
                cashDebtRatioReady = cashDebtRatioReady || reportsContainField(result.output(), "cashToDebt");
                inventoryTurnoverReady = inventoryTurnoverReady || reportsContainField(
                        result.output(),
                        "inventoryTurnover",
                        "inventoryTurnoverDays"
                );
                inventoryBalanceReady = inventoryBalanceReady || reportsContainField(result.output(), "inventory");
                operatingLiabilityReady = operatingLiabilityReady || reportsContainField(
                        result.output(),
                        "operatingLiabilitiesEstimate",
                        "operatingLiabilityBreakdown",
                        "accountsPayable",
                        "contractLiabilities"
                );
                parentEquityReady = parentEquityReady || reportsContainField(result.output(), "totalParentEquity");
                continue;
            }

            if (normalizeToolName("Search for information").equals(normalizedToolName)) {
                String searchEvidence = extractSearchEvidenceText(result.output());
                if (!StringUtils.hasText(searchEvidence)) {
                    continue;
                }
                String lowerEvidence = searchEvidence.toLowerCase(Locale.ROOT);
                Set<String> coveredGapKeys = new LinkedHashSet<>(result.coveredGapKeys());

                if (coveredGapKeys.contains(GapKey.DEDUCT_ROE.code())
                        && containsAny(lowerEvidence, "扣非roe", "扣非净资产收益率", "净资产收益率")) {
                    deductRoeReady = true;
                }
                if (coveredGapKeys.contains(GapKey.GROSS_MARGIN_PEERS.code())
                        && containsAny(lowerEvidence, "竞争对手", "可比公司", "同行", "同业", "主营业务")) {
                    peersReady = true;
                }
                if (coveredGapKeys.contains(GapKey.CASH_SALES_RATIO.code())
                        && containsAny(lowerEvidence, "销售商品", "提供劳务收到的现金", "现金收入比率", "营业收入")) {
                    cashSalesRatioReady = true;
                }
                if (coveredGapKeys.contains(GapKey.PROFIT_QUALITY.code())
                        && containsAny(lowerEvidence, "净利润", "现金含金量", "经营活动产生的现金流量净额", "扣非净利润")) {
                    netMarginReady = true;
                    cashProfitRatioReady = true;
                    deductProfitReady = true;
                    parentNetProfitReady = true;
                }
                if (coveredGapKeys.contains(GapKey.LEVERAGE_DEBT.code())
                        && containsAny(lowerEvidence, "总资产周转率", "资产负债率", "带息债务", "权益乘数")) {
                    assetTurnoverReady = true;
                    debtAssetReady = true;
                    interestDebtAssetReady = true;
                }
                if (coveredGapKeys.contains(GapKey.CASH_DEBT.code())
                        && containsAny(lowerEvidence, "现金及其等价物", "带息债务", "现金债务比", "偿债")) {
                    cashReady = true;
                    interestDebtReady = true;
                }
                if (coveredGapKeys.contains(GapKey.INVENTORY_TURNOVER.code())
                        && containsAny(lowerEvidence, "存货周转率", "存货周转天数", "存货周转", "营业成本", "平均存货")) {
                    inventoryTurnoverReady = true;
                }
                if (coveredGapKeys.contains(GapKey.OPERATING_LIABILITIES.code())
                        && containsAny(lowerEvidence, "经营性负债", "应付账款", "合同负债", "应付票据")) {
                    operatingLiabilityReady = true;
                }
                if (coveredGapKeys.contains(GapKey.RISK_FREE_RATE.code())
                        && containsAny(lowerEvidence, "10年期国债", "十年期国债", "国债收益率", "无风险收益率")) {
                    riskFreeRateReady = true;
                }
                if (coveredGapKeys.contains(GapKey.CAPITAL_FLOW_SIGNAL.code())
                        && containsAny(lowerEvidence, "增持", "减持", "险资", "保险资金", "机构持仓", "资金流向")) {
                    capitalFlowReady = true;
                }
                if (coveredGapKeys.contains(GapKey.VALUATION_MARKER.code())
                        && containsAny(lowerEvidence, "股息率", "分红收益率", "市盈率", "pe", "pb", "估值", "自由现金流收益率", "ev/ebit")) {
                    valuationMarkerReady = true;
                }
            }
        }

        if (requiredGapKeys.contains(GapKey.DEDUCT_ROE.code())
                && (deductRoeReady || (deductProfitReady && parentEquityReady))) {
            satisfiedGapKeys.add(GapKey.DEDUCT_ROE.code());
        }
        if (requiredGapKeys.contains(GapKey.CASH_SALES_RATIO.code())
                && (cashSalesRatioReady || (revenueReady && salesCashReady))) {
            satisfiedGapKeys.add(GapKey.CASH_SALES_RATIO.code());
        }
        if (requiredGapKeys.contains(GapKey.GROSS_MARGIN_PEERS.code()) && grossMarginReady && peersReady) {
            satisfiedGapKeys.add(GapKey.GROSS_MARGIN_PEERS.code());
        }
        if (requiredGapKeys.contains(GapKey.PROFIT_QUALITY.code())
                && netMarginReady
                && (cashProfitRatioReady || (operatingCashflowReady && netProfitReady))
                && (deductProfitRatioReady || (deductProfitReady && parentNetProfitReady))) {
            satisfiedGapKeys.add(GapKey.PROFIT_QUALITY.code());
        }
        if (requiredGapKeys.contains(GapKey.LEVERAGE_DEBT.code())
                && assetTurnoverReady
                && debtAssetReady
                && (interestDebtAssetReady || (interestDebtReady && totalAssetsReady))) {
            satisfiedGapKeys.add(GapKey.LEVERAGE_DEBT.code());
        }
        if (requiredGapKeys.contains(GapKey.CASH_DEBT.code())
                && (cashDebtRatioReady || (cashReady && interestDebtReady))) {
            satisfiedGapKeys.add(GapKey.CASH_DEBT.code());
        }
        if (requiredGapKeys.contains(GapKey.INVENTORY_TURNOVER.code())
                && (inventoryTurnoverReady || (operatingCostReady && inventoryBalanceReady))) {
            satisfiedGapKeys.add(GapKey.INVENTORY_TURNOVER.code());
        }
        if (requiredGapKeys.contains(GapKey.OPERATING_LIABILITIES.code()) && operatingLiabilityReady) {
            satisfiedGapKeys.add(GapKey.OPERATING_LIABILITIES.code());
        }
        if (requiredGapKeys.contains(GapKey.RISK_FREE_RATE.code()) && riskFreeRateReady) {
            satisfiedGapKeys.add(GapKey.RISK_FREE_RATE.code());
        }
        if (requiredGapKeys.contains(GapKey.CAPITAL_FLOW_SIGNAL.code()) && capitalFlowReady) {
            satisfiedGapKeys.add(GapKey.CAPITAL_FLOW_SIGNAL.code());
        }
        if (requiredGapKeys.contains(GapKey.VALUATION_MARKER.code()) && valuationMarkerReady) {
            satisfiedGapKeys.add(GapKey.VALUATION_MARKER.code());
        }
        return satisfiedGapKeys;
    }

    private boolean reportsContainField(String output, String... fieldNames) {
        if (!StringUtils.hasText(output) || fieldNames == null || fieldNames.length == 0) {
            return false;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(output);
            if (!"SUCCESS".equalsIgnoreCase(root.path("status").asText(""))) {
                return false;
            }

            JsonNode reports = root.path("reports");
            if (!reports.isArray()) {
                return false;
            }

            for (JsonNode report : reports) {
                for (String fieldName : fieldNames) {
                    JsonNode fieldNode = report.path(fieldName);
                    if (!fieldNode.isMissingNode() && !fieldNode.isNull() && StringUtils.hasText(fieldNode.asText(""))) {
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to inspect financial report output for gap coverage", ex);
        }
        return false;
    }

    private String extractSearchEvidenceText(String output) {
        if (!StringUtils.hasText(output)) {
            return "";
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(output);
            StringBuilder evidence = new StringBuilder();
            appendIfPresent(evidence, root.path("answer").asText(""));

            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    appendIfPresent(evidence, item.path("title").asText(""));
                    appendIfPresent(evidence, item.path("content").asText(""));
                }
            }
            return evidence.toString().trim();
        } catch (Exception ex) {
            log.debug("Failed to inspect search output for gap coverage", ex);
            return "";
        }
    }

    private void appendIfPresent(StringBuilder builder, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(value.trim());
    }

    private boolean isSuccessfulToolResult(ToolExecutionResult result) {
        if (result == null || !StringUtils.hasText(result.output())) {
            return false;
        }

        String normalized = result.output().replace("\r", "").trim();
        if (normalized.contains("工具执行失败")) {
            return false;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        return !lower.contains("\"status\": \"error\"")
                && !lower.contains("\"status\":\"error\"")
                && !lower.contains("\"query\": \"error\"")
                && !lower.contains("\"query\":\"error\"");
    }

    private ValueAnalysisPreparation withMissingData(ValueAnalysisPreparation preparation, List<String> missingData) {
        return new ValueAnalysisPreparation(
                preparation.scope(),
                preparation.target(),
                preparation.stockQuery(),
                preparation.mentionedTargets(),
                preparation.assetType(),
                preparation.reason(),
                uniqueNonBlank(missingData)
        );
    }

    private LinkedHashMap<String, Object> financialReportArguments(String stockQuery, int limit, String period, String focus) {
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("stockQuery", stockQuery);
        arguments.put("limit", limit);
        arguments.put("period", period);
        arguments.put("focus", focus);
        return arguments;
    }

    private LinkedHashMap<String, Object> linkedArguments(String key, Object value) {
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(key, value);
        return arguments;
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
            com.fasterxml.jackson.databind.node.ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
            node.forEach(child -> arrayNode.add(sortJsonNode(child)));
            return arrayNode;
        }

        com.fasterxml.jackson.databind.node.ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        fieldNames.stream()
                .sorted()
                .forEach(fieldName -> objectNode.set(fieldName, sortJsonNode(node.get(fieldName))));
        return objectNode;
    }

    private ToolExecutionResult executeTool(String focus,
                                            String toolName,
                                            Map<String, Object> arguments,
                                            List<String> coveredGapKeys) {
        ToolCallback toolCallback = findToolCallback(toolName);
        if (toolCallback == null) {
            log.warn("Tool not found for value analysis: {}", toolName);
            return new ToolExecutionResult(
                    focus,
                    toolName,
                    String.valueOf(arguments),
                    "工具未找到，无法执行。",
                    coveredGapKeys
            );
        }

        try {
            String argumentsJson = OBJECT_MAPPER.writeValueAsString(arguments);
            String output = toolCallback.call(argumentsJson);
            return new ToolExecutionResult(
                    focus,
                    toolCallback.getName(),
                    argumentsJson,
                    output,
                    coveredGapKeys
            );
        } catch (Exception ex) {
            log.warn("Failed to execute tool={} for value analysis", toolName, ex);
            return new ToolExecutionResult(
                    focus,
                    toolName,
                    String.valueOf(arguments),
                    "工具执行失败：" + ex.getMessage(),
                    coveredGapKeys
            );
        }
    }

    private ToolCallback findToolCallback(String requestedToolName) {
        String normalizedRequestedName = normalizeToolName(requestedToolName);
        for (ToolCallback tool : allTools) {
            if (normalizeToolName(tool.getName()).equals(normalizedRequestedName)) {
                return tool;
            }
        }
        return null;
    }

    private String normalizeToolName(String toolName) {
        return String.valueOf(toolName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "");
    }

    private String buildFinalPrompt(String message,
                                    ValueAnalysisPreparation preparation,
                                    String ragContext,
                                    List<ToolExecutionResult> toolResults) {
        String currentTimeSummary = summarizeCurrentTimeToolResult(toolResults);
        String toolContext = toolResults.isEmpty()
                ? "本轮没有查到有效的实时工具结果，请直接说明哪些事实仍待确认。"
                : toolResults.stream()
                .map(result -> """
                        [%s]
                        工具：%s
                        调用参数：%s
                        工具输出：
                        %s
                        """.formatted(result.focus(), result.toolName(), result.argumentsJson(), result.output()))
                .collect(Collectors.joining("\n"));

        return """
                用户原始问题：
                %s

                当前时间校验：
                %s

                本轮分析标的：
                %s

                分析范围提醒：
                - 当前模式只分析单只股票或单家上市公司
                - 当前阶段先做财报质量分析，重点覆盖近三年
                - 财务分析完成后，必须继续给出“周行五步法”
                - 对比维度优先找业务相近、规模接近的上市公司
                - 涉及当前日期、时区或“截至今天/本周/本月”等相对时间时，必须以“Get current time”工具结果为准；若本轮没有该结果，就明确说明未校验当前时间，不能默认按北京时间推断

                当前仍缺的重要数据：
                %s

                财报分析步骤与判断标准：
                1. 扣非 ROE：最好常年 15%% 以上，至少 10%%。
                2. 现金收入比率：销售商品、提供劳务收到的现金 / 营业收入，至少大于 0.8，最好大于 1。
                3. 毛利率：先列竞争对手明细，再比较近三年毛利率。
                4. 净利润：要看净利润率、净利润现金含金量（经营活动产生的现金流量净额 / 净利润）、扣非净利润 / 净利润，后者通常以 0.8 到 1.2 为相对健康区间。
                5. 总资产周转率与杠杆：要同时看总资产周转率、资产负债率、带息债务 / 总资产，并解释权益乘数与经营性负债。
                6. 现金及其等价物 / 带息债务：越高通常代表偿债安全垫越厚，但要结合行业特性解释。
                7. 财务健康判断：前 6 节结束后，必须明确判断“合格且健康 / 基本合格但需跟踪 / 暂不合格或存在明显风险”。

                缺失指标补算优先级：
                1. 扣非 ROE：优先使用 deductRoeWeighted 或 deductRoeEstimate；若两者都没有，再用 deductParentNetProfit / 平均 totalParentEquity 估算。
                2. 现金收入比率：优先使用 cashSalesRatio；若没有，就用 salesCashReceived / revenue 计算。
                3. 净利润现金含金量：优先使用 operatingCashflowToNetProfit；若没有，就用 operatingCashflow 或 operatingNetCashflow / netProfit 计算。
                4. 扣非净利润占比：优先使用 deductParentNetProfitRatio；若没有，就用 deductParentNetProfit / parentNetProfit 计算。
                5. 带息债务 / 总资产：优先使用 interestBearingDebtToAsset；若没有，就用 interestBearingDebtEstimate / totalAssets 计算。
                6. 现金及其等价物 / 带息债务：优先使用 cashToDebt；若没有，就用 cashAndEquivalentsEnd / interestBearingDebtEstimate 计算。
                7. 存货周转率：优先使用 inventoryTurnover；若没有，就用 operatingCost / 平均 inventory 估算；存货周转天数可再用 365 / inventoryTurnover 估算。
                8. 经营性负债：优先使用 operatingLiabilitiesEstimate；若没有，就尝试汇总 accountsPayable、notesPayableOperating、contractLiabilities、staffSalaryPayable、taxPayable、otherPayable。

                周行五步法补充要求：
                1. 先提醒用户只能用闲钱做长线。
                2. 用一句话抓住公司主要矛盾。
                3. 列出每日/每周跟踪指标，默认包含中国 10 年期国债收益率；若标的是高股息/银行/公用事业，可补充股息率和险资动向；若不是收益型资产，则换成更匹配的行业景气或经营跟踪指标，并写明替换理由。
                4. 判断估值贵还是便宜；高股息资产优先比较股息率与无风险收益率，其他资产用更合适的估值锚。
                5. 结合市场情绪给出等待、买入、持有、减仓、回补条件。

                本地知识库（偏方法论，不代表实时事实）：
                %s

                实时工具证据：
                %s

                输出要求补充：
                1. 必须严格使用以下 Markdown 二级标题，顺序不可改变：
                   - ## 1. 扣非 ROE
                   - ## 2. 营业收入与现金收入比率
                   - ## 3. 毛利率与同行对比
                   - ## 4. 净利润与利润质量
                   - ## 5. 总资产周转率与杠杆
                   - ## 6. 现金及其等价物 / 带息债务
                   - ## 7. 财务健康判断
                   - ## 8. 周行五步法
                   - ## 最终投资建议
                   - ## 还缺什么数据
                2. 前 6 节尽量给近三年数据；拿不满 3 年时，直接说明缺了哪些年份。
                3. 每一节先给一句结论，再分别列“事实”和“推理”。
                4. 如果找到了竞争对手，先列“竞争对手明细”，再做横向和纵向对比；如果没找到，就明确写“暂未确认到合适的可比公司”。
                5. 不要编造数据。遇到数据口径不一致或搜索结果冲突时，直接说明冲突点。
                6. 只要引用了当前时间相关结论，就必须写清楚采用的时区或时间基准。
                7. 如果已经拿到当前时间结果，而用户问题不只是单独问时间，你必须把它当作时间边界继续完成剩余财报分析，不能只复述时间工具输出。
                8. “## 8. 周行五步法”里必须显式写出第 1 步到第 5 步，不能省略。
                9. “## 最终投资建议”必须把资金属性、财务健康、估值、跟踪指标和市场情绪综合起来给出结论。
                10. 如果某个指标没有直接值，但工具结果已经给了可计算它的原始字段，你必须先计算，再写入对应章节，不能直接把它丢进“## 还缺什么数据”。
                11. 只有在直接值和可计算所需原始字段都缺失时，才能把它列入“## 还缺什么数据”，并明确写出究竟缺了哪个分子、分母或期初/期末字段。
                """.formatted(
                message,
                currentTimeSummary,
                firstNonBlank(preparation.target(), preparation.stockQuery()),
                formatList(preparation.missingData()),
                ragContext,
                toolContext
        );
    }

    private String summarizeCurrentTimeToolResult(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "- 未查询到当前时间工具结果。";
        }

        String normalizedCurrentTimeToolName = normalizeToolName("Get current time");
        for (ToolExecutionResult result : toolResults) {
            if (result == null || !normalizedCurrentTimeToolName.equals(normalizeToolName(result.toolName()))) {
                continue;
            }
            String output = normalizeValue(result.output());
            if (!StringUtils.hasText(output)) {
                return "- 已调用当前时间工具，但返回内容为空。";
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
                    return "- 已校验当前时间：%s（时区：%s%s）".formatted(
                            firstNonBlank(currentTime, "未返回具体时间"),
                            firstNonBlank(timezone, "未返回时区"),
                            StringUtils.hasText(offset) ? "，偏移：" + offset : ""
                    );
                }
            } catch (Exception ignored) {
                return "- 当前时间工具原始输出：" + output;
            }
            return "- 当前时间工具原始输出：" + output;
        }
        return "- 未查询到当前时间工具结果。";
    }

    private String formatList(List<String> values) {
        List<String> safeValues = uniqueNonBlank(values);
        if (safeValues.isEmpty()) {
            return "- 暂无";
        }
        return safeValues.stream()
                .map(value -> "- " + value)
                .collect(Collectors.joining("\n"));
    }

    private ChatTrace buildTrace(List<ToolExecutionResult> toolResults,
                                 List<FundAppLocalDocumentRetriever.RetrievedDocument> ragDocuments) {
        List<ChatTraceToolCall> toolCalls = toolResults == null ? List.of() : toolResults.stream()
                .map(result -> new ChatTraceToolCall(
                        StringUtils.hasText(result.focus()) ? result.focus().trim() : null,
                        StringUtils.hasText(result.toolName()) ? result.toolName().trim() : null,
                        abbreviate(result.argumentsJson(), MAX_TRACE_ARGUMENT_LENGTH),
                        normalizeValue(result.output())
                ))
                .filter(item -> StringUtils.hasText(item.title())
                        || StringUtils.hasText(item.toolName())
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

    private List<String> defaultMissingData() {
        return List.of(
                "近三年扣非 ROE",
                "近三年销售商品、提供劳务收到的现金 / 营业收入",
                "近三年毛利率及可比公司对照",
                "近三年净利润率、净利润现金含金量、扣非净利润 / 净利润",
                "近三年总资产周转率、资产负债率、带息债务 / 总资产",
                "近三年现金及其等价物 / 带息债务",
                "近三年存货周转率与存货周转天数",
                "近三年经营性负债及结构",
                "最新 10 年期国债收益率（无风险收益率）",
                "近期机构增减持 / 险资动向",
                "当前估值锚（股息率 / PE / PB 等）"
        );
    }

    private List<String> mergeLists(List<String> left, List<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged.stream().toList();
    }

    private List<String> uniqueNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                unique.add(value.trim());
            }
        }
        return unique.stream().toList();
    }

    private List<String> mergeMissingData(List<String> current, List<String> additions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (current != null) {
            merged.addAll(current);
        }
        if (additions != null) {
            merged.addAll(additions);
        }
        return merged.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private boolean looksLikeNonStockTopic(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean containsNonStockKeyword = NON_STOCK_KEYWORDS.stream().anyMatch(normalized::contains);
        if (!containsNonStockKeyword) {
            return false;
        }
        if (!extractExplicitStockIdentifiers(message).isEmpty()) {
            return false;
        }
        String targetCandidate = extractTargetCandidate(message);
        return !looksLikeStockTarget(targetCandidate);
    }

    private List<String> extractExplicitStockIdentifiers(String message) {
        if (!StringUtils.hasText(message)) {
            return List.of();
        }
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = EMBEDDED_STOCK_IDENTIFIER_PATTERN.matcher(message);
        while (matcher.find()) {
            String token = matcher.group();
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String normalized = token.trim();
            String uppercase = normalized.toUpperCase(Locale.ROOT);
            if (ENGLISH_TICKER_STOP_WORDS.contains(uppercase)) {
                continue;
            }
            if (normalized.matches("(?i)^[A-Z][A-Z0-9\\.-]{0,4}(?:\\.US)?$") && normalized.length() < 2) {
                continue;
            }
            identifiers.add(normalized);
        }
        return identifiers.stream().toList();
    }

    private List<String> detectMultipleTargets(String message) {
        List<String> explicitIdentifiers = extractExplicitStockIdentifiers(message);
        if (explicitIdentifiers.size() > 1) {
            return explicitIdentifiers;
        }

        if (!containsAnyHint(message, MULTI_TARGET_HINTS)
                && !message.contains("、")
                && !message.contains("，")
                && !message.contains(",")
                && !message.contains("和")
                && !message.contains("与")) {
            return List.of();
        }

        String cleaned = message
                .replaceAll("[！？。；：()（）【】\\[\\]{}<>\"“”‘’]", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|麻烦|查一下|查下|查询|搜一下|搜下|看一下|看下|想看|想查|帮忙|分析一下|分析|点评一下|解读一下|对比一下|对比|比较一下|比较|告诉我|觉得|如何|怎么样|值不值得|值不值得买|值得买吗|能不能买|能买吗)", " ")
                .replaceAll("\\s+", " ")
                .trim();

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String part : MULTI_TARGET_SEPARATOR_PATTERN.split(cleaned)) {
            String candidate = extractTargetCandidate(part);
            if (looksLikeStockTarget(candidate)) {
                targets.add(candidate.trim());
            }
        }
        return targets.stream().toList();
    }

    private boolean containsAnyHint(String message, Set<String> hints) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return hints.stream().anyMatch(normalized::contains);
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

    private boolean looksLikeStockTarget(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        String normalized = candidate.trim();
        if (normalized.length() < 2 || normalized.length() > 32) {
            return false;
        }
        String uppercase = normalized.toUpperCase(Locale.ROOT);
        if (ENGLISH_TICKER_STOP_WORDS.contains(uppercase)) {
            return false;
        }
        if (EXACT_STOCK_IDENTIFIER_PATTERN.matcher(uppercase).matches()) {
            return true;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (NON_STOCK_KEYWORDS.stream().anyMatch(lower::contains)) {
            return false;
        }
        if (COMPANY_NAME_STOP_WORDS.stream().anyMatch(lower::contains)) {
            return false;
        }
        if (normalized.matches(".*\\d{5,6}.*")) {
            return true;
        }
        if (normalized.matches(".*[A-Za-z]{2,10}.*")) {
            return true;
        }
        return normalized.matches(".*[\\u4e00-\\u9fa5]{2,16}.*");
    }

    private String extractTargetCandidate(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }

        List<String> explicitIdentifiers = extractExplicitStockIdentifiers(message);
        if (explicitIdentifiers.size() == 1) {
            return explicitIdentifiers.get(0);
        }

        String cleaned = message
                .replaceAll("[，。！？、：；（）【】《》“”‘’,.!?;:()/\\\\\\[\\]{}<>\"']", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|麻烦|查一下|查下|查询|搜一下|搜下|看一下|看下|想看|想查|帮忙|分析一下|分析|拆解一下|拆解|判断一下|判断|点评一下|解读一下|告诉我|对比一下|对比|比较一下|比较|值不值得买|值得买吗|能买吗|适合长期持有吗|长期|短期|现在|最新|当前|今日|今天|截至今天|截至昨日|截至目前|截至现在|截止今天|截止昨日|截止目前|截止现在|截至|截止|(?:最近|近|过去)\\s*[0-9一二三四五六七八九十两]+年|股价|价格|多少钱|多少|是多少|行情|走势|报价|现价|盘中|收盘价|呢|吗|财报|年报|季报|半年报|股票|基金|指数|a股|港股|美股|公司|企业|上市公司|模式|护城河|管理层|估值|安全边际|风险|扣非|roe|roic|毛利率|净利润|营业收入|资产负债率|带息债务|现金流|现金及其等价物|总资产|总负债|周转率|竞争对手|明细|步骤)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleanCandidateTokens(cleaned);
        if (!StringUtils.hasText(cleaned)) {
            return "";
        }

        if (containsAnyHint(message, MULTI_TARGET_HINTS)) {
            List<String> targets = detectMultipleTargets(message);
            if (targets.size() > 1) {
                return "";
            }
        }

        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40).trim();
    }

    private String cleanCandidateTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        List<String> tokens = new ArrayList<>();
        for (String token : value.split("\\s+")) {
            String normalized = token == null ? "" : token.trim();
            normalized = normalized.replaceAll("(?iu)(?:最近|近|过去)\\s*[0-9一二三四五六七八九十两]+年", "").trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (normalized.equals("/")
                    || normalized.equals("\\")
                    || normalized.equals("请")
                    || normalized.equals("和")
                    || normalized.equals("与")
                    || normalized.equals("及")
                    || normalized.equals("截至")
                    || normalized.equals("截止")
                    || normalized.equals("截至今天")
                    || normalized.equals("截至昨日")
                    || normalized.equals("截至目前")
                    || normalized.equals("截至现在")
                    || normalized.equals("截止今天")
                    || normalized.equals("截止昨日")
                    || normalized.equals("截止目前")
                    || normalized.equals("截止现在")) {
                continue;
            }
            tokens.add(normalized);
        }

        String cleaned = String.join(" ", tokens).trim();
        if (cleaned.matches(".*[\\u4e00-\\u9fa5].*")) {
            return cleaned.replace(" ", "");
        }
        return cleaned;
    }

    private String cleanupValue(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
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

    private String finalizeReply(String chatId, String userMessage, String content, ChatTrace trace) {
        chatMemoryMetadataService.enrichLatestExchange(chatId, userMessage, trace);
        return content;
    }

    private Flux<String> finalizeStreamReply(Flux<String> stream, String chatId, String userMessage, ChatTrace trace) {
        return stream.doOnComplete(() -> chatMemoryMetadataService.enrichLatestExchange(chatId, userMessage, trace));
    }

    private void recordDirectReply(String chatId, String userMessage, String assistantReply, ChatTrace trace) {
        chatMemoryMetadataService.recordExchange(chatId, userMessage, assistantReply, trace);
    }

    private ChatClient createValueAnalysisChatClient(String modelId) {
        return ChatClient.builder(chatModelRegistryService.getChatModel(modelId))
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .chatMemoryRetrieveSize(20)
                                .build(),
                        new MyLoggerAdvisor()
                )
                .build();
    }

    private ChatClient createPreparationClient(String modelId) {
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

    private record PreparedValueAnalysis(ValueAnalysisPreparation preparation,
                                         String finalPrompt,
                                         String directReply,
                                         ChatTrace trace,
                                         boolean shouldRecordDirectReply) {
    }

    private record ValueAnalysisPreparation(AnalysisScope scope,
                                            String target,
                                            String stockQuery,
                                            List<String> mentionedTargets,
                                            String assetType,
                                            String reason,
                                            List<String> missingData) {
    }

    private record SearchTask(String focus, String query, List<String> coveredGapKeys) {

        private SearchTask {
            coveredGapKeys = coveredGapKeys == null ? List.of() : List.copyOf(coveredGapKeys);
        }
    }

    private record CollectedRealtimeEvidence(List<ToolExecutionResult> results,
                                             List<String> remainingMissingData) {
    }

    private record PlannedToolCall(String focus,
                                   String toolName,
                                   Map<String, Object> arguments,
                                   List<String> coveredGapKeys) {

        private PlannedToolCall {
            arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
            coveredGapKeys = coveredGapKeys == null ? List.of() : List.copyOf(coveredGapKeys);
        }

        private String argumentsJson() {
            try {
                return OBJECT_MAPPER.writeValueAsString(arguments);
            } catch (Exception ex) {
                return String.valueOf(arguments);
            }
        }
    }

    private record ToolExecutionResult(String focus,
                                       String toolName,
                                       String argumentsJson,
                                       String output,
                                       List<String> coveredGapKeys) {

        private ToolExecutionResult {
            coveredGapKeys = coveredGapKeys == null ? List.of() : List.copyOf(coveredGapKeys);
        }
    }

    private record RetrievedRagContext(String context,
                                       List<FundAppLocalDocumentRetriever.RetrievedDocument> documents) {
    }

    private enum GapKey {
        DEDUCT_ROE("deduct_roe", "近三年扣非 ROE"),
        CASH_SALES_RATIO("cash_sales_ratio", "近三年销售商品、提供劳务收到的现金 / 营业收入"),
        GROSS_MARGIN_PEERS("gross_margin_peers", "近三年毛利率及可比公司对照"),
        PROFIT_QUALITY("profit_quality", "近三年净利润率、净利润现金含金量、扣非净利润 / 净利润"),
        LEVERAGE_DEBT("leverage_debt", "近三年总资产周转率、资产负债率、带息债务 / 总资产"),
        CASH_DEBT("cash_debt", "近三年现金及其等价物 / 带息债务"),
        INVENTORY_TURNOVER("inventory_turnover", "近三年存货周转率与存货周转天数"),
        OPERATING_LIABILITIES("operating_liabilities", "近三年经营性负债及结构"),
        RISK_FREE_RATE("risk_free_rate", "最新 10 年期国债收益率（无风险收益率）"),
        CAPITAL_FLOW_SIGNAL("capital_flow_signal", "近期机构增减持 / 险资动向"),
        VALUATION_MARKER("valuation_marker", "当前估值锚（股息率 / PE / PB 等）");

        private final String code;
        private final String label;

        GapKey(String code, String label) {
            this.code = code;
            this.label = label;
        }

        private String code() {
            return code;
        }

        private String label() {
            return label;
        }

        private static GapKey fromCode(String code) {
            for (GapKey value : values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            return null;
        }
    }

    private enum AnalysisScope {
        SINGLE_STOCK,
        MULTIPLE_STOCKS,
        NON_STOCK,
        MISSING_TARGET
    }
}
