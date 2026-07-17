package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RAG 引用信息")
public record ChatTraceReference(
        @Schema(description = "资料来源", example = "价值投资-分析清单")
        String source,
        @Schema(description = "引用片段", example = "ROE 需要结合现金流和利润质量一起看...")
        String snippet
) {
}
