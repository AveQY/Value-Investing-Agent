package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "聊天历史响应")
public record ChatHistoryResponse(
        @Schema(description = "会话 ID", example = "chat-001")
        String chatId,
        @Schema(description = "消息总数", example = "2")
        int messageCount,
        @Schema(description = "聊天历史消息")
        List<ChatHistoryMessage> messages,
        @Schema(description = "响应时间")
        Instant timestamp
) {
}
