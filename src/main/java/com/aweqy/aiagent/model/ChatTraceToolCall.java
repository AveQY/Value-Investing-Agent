package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "工具调用信息")
public record ChatTraceToolCall(
        @Schema(description = "本次调用关注点", example = "毛利率与竞争对手")
        String title,
        @Schema(description = "工具名称", example = "Search for information")
        String toolName,
        @Schema(description = "调用参数", example = "{\"query\":\"贵州茅台 最近5年 毛利率\"}")
        String arguments,
        @Schema(description = "结果摘要", example = "检索到近五年毛利率与行业对比资料...")
        String outputPreview
) {
}
