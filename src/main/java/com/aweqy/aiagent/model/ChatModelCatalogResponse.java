package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "可用聊天模型列表响应")
public record ChatModelCatalogResponse(
        @Schema(description = "默认模型 ID", example = "deepseek-r1")
        String defaultModelId,
        @Schema(description = "可用模型列表")
        List<ChatModelSummary> models,
        @Schema(description = "响应时间")
        Instant timestamp
) {
}
