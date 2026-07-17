package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "删除聊天历史响应")
public record DeleteHistoryResponse(
        @Schema(description = "会话 ID", example = "chat-001")
        String chatId,
        @Schema(description = "删除结果说明", example = "聊天历史已删除")
        String message,
        @Schema(description = "响应时间")
        Instant timestamp
) {
}
