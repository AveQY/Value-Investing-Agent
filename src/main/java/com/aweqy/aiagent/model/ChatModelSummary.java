package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "单个聊天模型摘要")
public record ChatModelSummary(
        @Schema(description = "模型配置 ID", example = "deepseek-r1")
        String id,
        @Schema(description = "展示名称", example = "DeepSeek R1")
        String name,
        @Schema(description = "模型类型", example = "openai-compatible")
        String provider,
        @Schema(description = "基础地址", example = "https://api.edgefn.net")
        String baseUrl,
        @Schema(description = "远端模型 ID", example = "DeepSeek-R1-0528-Qwen3-8B")
        String providerModelId,
        @Schema(description = "是否为预置模型", example = "true")
        boolean preset,
        @Schema(description = "是否为自定义模型", example = "false")
        boolean custom,
        @Schema(description = "是否为默认模型", example = "true")
        boolean defaultModel,
        @Schema(description = "脱敏后的 API Key", example = "sk-1234...abcd")
        String apiKeyMasked
) {
}
