package com.aweqy.aiagent.service;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FinanceQuestionClassifier {

    private static final Set<String> FINANCE_KEYWORDS = Set.of(
            "财经", "金融", "投资", "理财", "资产配置", "风险偏好", "回撤", "波动率",
            "股票", "个股", "股价", "行情", "报价", "现价", "涨跌", "涨停", "跌停",
            "开盘", "收盘", "成交量", "成交额", "市值", "估值", "市盈率", "pe", "pb",
            "市净率", "roe", "roic", "现金流", "营收", "收入", "利润", "净利润", "毛利率",
            "财报", "年报", "季报", "半年报", "业绩", "公告", "分红", "派息", "回购",
            "现金及其等价物", "现金及现金等价物", "现金等价物", "货币资金", "带息债务", "有息负债",
            "资产负债率", "总资产", "总资产周转率", "净资产收益率", "现金收入比率", "现金含金量",
            "扣非", "扣非净利润", "经营现金流", "经营活动现金流", "偿债", "杠杆", "债务",
            "研报", "券商", "基金", "etf", "lof", "reits", "债券", "可转债", "国债",
            "指数", "上证", "深证", "创业板", "科创板", "北交所", "沪深300", "中证",
            "恒生", "纳斯达克", "标普", "道琼斯", "港股", "美股", "a股", "持仓", "仓位",
            "建仓", "加仓", "减仓", "买入", "卖出", "止损", "止盈", "定投", "基金经理",
            "行业", "板块", "赛道", "龙头", "巴菲特", "芒格", "段永平", "央行", "美联储",
            "利率", "汇率", "通胀", "fomc", "ipo", "融资融券", "龙虎榜", "latest", "price",
            "quote", "market", "intraday", "filing", "earnings", "guidance", "valuation"
    );
    private static final Set<String> VALUE_INVESTING_KEYWORDS = Set.of(
            "价值", "长期价值", "内在价值", "护城河", "安全边际", "能力圈", "商业模式", "管理层",
            "资本配置", "股东回报", "竞争优势", "自由现金流", "复利", "市场先生", "企业质量",
            "生意模式", "长期持有", "买点", "卖点", "仓位管理"
    );
    private static final Set<String> FINANCE_INTENT_KEYWORDS = Set.of(
            "查", "查询", "看", "分析", "诊断", "解读", "点评", "判断", "追踪", "关注",
            "能买吗", "可以买", "能不能买", "值得买吗", "怎么投", "怎么看", "走势", "目标价",
            "基本面", "长期", "短期", "价格", "多少钱", "多少", "表现", "机会", "风险"
    );
    private static final Set<String> NON_FINANCE_HINTS = Set.of(
            "景点", "旅游", "天气", "美食", "电影", "电视剧", "音乐", "歌曲", "小说", "作文",
            "翻译", "图片", "视频", "摄影", "游戏", "小游戏", "贪吃蛇", "代码", "编程", "算法",
            "冒泡排序", "排序", "java", "python", "sql", "html", "http", "api", "pdf", "ppt",
            "简历", "面试", "营养", "水果", "做法", "菜谱", "健身", "减肥", "景区"
    );
    private static final Set<String> CONTEXT_FOLLOW_UP_HINTS = Set.of(
            "继续", "展开", "详细", "细说", "具体", "深入", "补充", "还有呢", "然后呢",
            "那", "那么", "这个", "这个呢", "这个怎么看", "它", "它呢", "这只", "这家公司",
            "能买吗", "能不能买", "值不值得买", "贵吗", "便宜吗", "风险呢", "估值呢", "原因呢",
            "怎么看", "为什么", "依据呢", "继续说", "继续分析", "接着说", "再说说"
    );
    private static final Set<String> NON_FINANCE_TICKER_STOP_WORDS = Set.of(
            "AI", "API", "HTTP", "HTTPS", "HTML", "JSON", "JAVA", "SQL", "PDF", "TXT",
            "DOC", "PPT", "PPTX", "CSV", "XML", "OK", "HI", "HELLO", "HELP", "CODE"
    );
    private static final Pattern EXACT_SECURITY_IDENTIFIER_PATTERN = Pattern.compile(
            "(?i)^(?:sh|sz|bj)\\d{6}$|^(?:hk)\\d{5}$|^(?:gb_)[A-Z][A-Z0-9\\.-]{0,9}$"
                    + "|^\\d{6}(?:\\.(?:SH|SZ|BJ))?$|^\\d{5}(?:\\.HK)?$|^[A-Z][A-Z0-9\\.-]{0,4}(?:\\.US)?$"
    );
    private static final Pattern EMBEDDED_CN_HK_CODE_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:sh|sz|bj)\\d{6}\\b|\\bhk\\d{5}\\b|(?<!\\d)\\d{6}(?!\\d)|(?<!\\d)\\d{5}(?!\\d))"
    );
    private static final Pattern EMBEDDED_US_TICKER_PATTERN = Pattern.compile(
            "\\b[A-Z][A-Z0-9\\.-]{0,4}(?:\\.US)?\\b"
    );

    public boolean isFinanceRelatedQuestion(String message) {
        String trimmed = StringUtils.trimWhitespace(message);
        if (!StringUtils.hasText(trimmed)) {
            return false;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (containsAnyKeyword(normalized, FINANCE_KEYWORDS)) {
            return true;
        }
        if (containsAnyKeyword(normalized, NON_FINANCE_HINTS)) {
            return false;
        }
        if (containsAnyKeyword(normalized, VALUE_INVESTING_KEYWORDS)) {
            return true;
        }

        String uppercase = trimmed.toUpperCase(Locale.ROOT);
        if (EXACT_SECURITY_IDENTIFIER_PATTERN.matcher(uppercase).matches()
                && !NON_FINANCE_TICKER_STOP_WORDS.contains(uppercase)) {
            return true;
        }

        if (looksLikeStandaloneSecurityTarget(trimmed)) {
            return true;
        }

        boolean hasFinanceIntent = containsAnyKeyword(normalized, FINANCE_INTENT_KEYWORDS);
        if (!hasFinanceIntent) {
            return false;
        }

        if (EMBEDDED_CN_HK_CODE_PATTERN.matcher(trimmed).find()) {
            return true;
        }

        Matcher tickerMatcher = EMBEDDED_US_TICKER_PATTERN.matcher(uppercase);
        while (tickerMatcher.find()) {
            String token = tickerMatcher.group();
            if (!NON_FINANCE_TICKER_STOP_WORDS.contains(token)) {
                return true;
            }
        }

        String targetCandidate = extractPotentialTarget(trimmed);
        if (hasFinanceIntent && looksLikePotentialSecurityTarget(targetCandidate)) {
            return true;
        }

        return false;
    }

    public boolean isClearlyNonFinanceQuestion(String message) {
        String trimmed = StringUtils.trimWhitespace(message);
        if (!StringUtils.hasText(trimmed)) {
            return true;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (!containsAnyKeyword(normalized, NON_FINANCE_HINTS)) {
            return false;
        }

        return !containsAnyKeyword(normalized, FINANCE_KEYWORDS)
                && !containsAnyKeyword(normalized, VALUE_INVESTING_KEYWORDS)
                && !looksLikeStandaloneSecurityTarget(trimmed)
                && !EMBEDDED_CN_HK_CODE_PATTERN.matcher(trimmed).find()
                && !containsUsTicker(trimmed.toUpperCase(Locale.ROOT));
    }

    public boolean isFinanceFollowUpQuestion(String message) {
        String trimmed = StringUtils.trimWhitespace(message);
        if (!StringUtils.hasText(trimmed)) {
            return false;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (containsAnyKeyword(normalized, NON_FINANCE_HINTS)) {
            return false;
        }
        if (containsAnyKeyword(normalized, FINANCE_KEYWORDS) || containsAnyKeyword(normalized, VALUE_INVESTING_KEYWORDS)) {
            return true;
        }
        return trimmed.length() <= 24 && containsAnyKeyword(normalized, CONTEXT_FOLLOW_UP_HINTS);
    }

    public boolean hasFinanceConversationContext(List<Message> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return false;
        }

        StringBuilder builder = new StringBuilder();
        for (Message message : recentMessages) {
            if (message == null || !StringUtils.hasText(message.getText())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(message.getText().trim());
        }
        return StringUtils.hasText(builder) && isFinanceRelatedQuestion(builder.toString());
    }

    private boolean looksLikeStandaloneSecurityTarget(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }

        String normalized = message.trim();
        if (normalized.length() > 12 || normalized.contains(" ")) {
            return false;
        }
        if (containsAnyKeyword(normalized.toLowerCase(Locale.ROOT), NON_FINANCE_HINTS)) {
            return false;
        }

        return looksLikePotentialSecurityTarget(normalized);
    }

    private boolean containsAnyKeyword(String normalizedMessage, Set<String> keywords) {
        return keywords.stream().anyMatch(normalizedMessage::contains);
    }

    private String extractPotentialTarget(String message) {
        String cleaned = message
                .replaceAll("[，。！？、：；（）【】《》“”‘’,.!?;:()\\[\\]{}<>\"']", " ")
                .replaceAll("(?iu)(请问|请帮我|帮我|麻烦|查一下|查下|查询|搜一下|搜下|看一下|看下|想看|想查|帮忙|介绍一下|分析一下|分析|拆解一下|拆解|判断一下|判断|点评一下|解读一下|告诉我|觉得|如何|怎么样|能不能买|能买吗|值得买吗|长期持有|长期|短期|买不买|买入吗|卖出吗|值不值得|现在|最新|当前|今日|今天|股价|价格|多少钱|多少|是多少|行情|走势|报价|现价|盘中|收盘价|股票|基金|指数|公司|企业|财报|公告|新闻|估值|护城河|安全边际|管理层|商业模式|能力圈|吗|呢|呀)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    private boolean looksLikePotentialSecurityTarget(String targetCandidate) {
        if (!StringUtils.hasText(targetCandidate)) {
            return false;
        }
        String candidate = targetCandidate.trim();
        if (candidate.length() < 2 || candidate.length() > 24) {
            return false;
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (containsAnyKeyword(normalized, NON_FINANCE_HINTS)) {
            return false;
        }
        if (candidate.matches(".*\\d{5,6}.*")) {
            return true;
        }
        if (candidate.matches(".*[A-Za-z]{2,10}.*")) {
            return true;
        }
        return candidate.matches(".*[\\u4e00-\\u9fa5]{2,12}.*");
    }

    private boolean containsUsTicker(String uppercaseMessage) {
        Matcher tickerMatcher = EMBEDDED_US_TICKER_PATTERN.matcher(uppercaseMessage);
        while (tickerMatcher.find()) {
            String token = tickerMatcher.group();
            if (!NON_FINANCE_TICKER_STOP_WORDS.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
