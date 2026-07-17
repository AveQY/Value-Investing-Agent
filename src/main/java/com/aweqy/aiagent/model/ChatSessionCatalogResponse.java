package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "会话列表响应")
public record ChatSessionCatalogResponse(
        @Schema(description = "会话摘要列表")
        List<ChatSessionSummary> sessions,
        @Schema(description = "响应时间")
        Instant timestamp
) {
}
