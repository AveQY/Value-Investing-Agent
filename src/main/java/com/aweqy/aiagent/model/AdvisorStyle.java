package com.aweqy.aiagent.model;

import org.springframework.util.StringUtils;

import java.util.Arrays;

public enum AdvisorStyle {

    DEFAULT(
            "default",
            "默认视角",
            ""
    ),
    BUFFETT_FRAMEWORK(
            "buffett_framework",
            "巴菲特框架",
            """
                    当前开启的分析视角：buffett_framework。
                    请优先使用巴菲特、芒格、段永平一脉的价值投资框架分析问题，重点关注：
                    1. 生意模式是否简单可理解
                    2. 护城河是否稳固
                    3. 管理层与资本配置是否可靠
                    4. 是否在能力圈内
                    5. 当前价格与安全边际是否匹配
                    6. 长期赔率和主要风险是什么

                    这是“框架视角”，不是角色扮演。
                    不要模仿个人口头禅，不要声称自己就是巴菲特本人。
                    """
    ),
    BUFFETT_PERSONA(
            "buffett_persona",
            "巴菲特表达",
            """
                    当前开启的分析视角：buffett_persona。
                    请继续坚持价值投资框架，但允许采用更接近巴菲特致股东信的表达 DNA：
                    - 朴素、直接、强调长期主义与概率
                    - 更偏重常识、商业本质和资本配置
                    - 尽量用易懂比喻解释复杂问题

                    重要边界：
                    1. 你不是巴菲特本人，不能虚构亲身经历、持仓或未公开观点。
                    2. 不能编造数据、公告或市场事实。
                    3. 表达可以更像“巴菲特式”，但结论必须保持专业、克制、可核查。
                    """
    );

    private final String code;
    private final String label;
    private final String systemPrompt;

    AdvisorStyle(String code, String label, String systemPrompt) {
        this.code = code;
        this.label = label;
        this.systemPrompt = systemPrompt;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public boolean hasSystemPrompt() {
        return StringUtils.hasText(systemPrompt);
    }

    public boolean isBuffettStyle() {
        return this == BUFFETT_FRAMEWORK || this == BUFFETT_PERSONA;
    }

    public static AdvisorStyle resolve(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(style -> style.code.equalsIgnoreCase(rawValue.trim()))
                .findFirst()
                .orElse(DEFAULT);
    }
}
