package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "聊天响应")
public record ChatResponse(
        @Schema(description = "会话 ID", example = "chat-001")
        String chatId,
        @Schema(description = "对话模式", example = "CHAT")
        String mode,
        @Schema(description = "实际使用的模型配置 ID", example = "deepseek-r1")
        String modelId,
        @Schema(description = "实际使用的模型名称", example = "DeepSeek R1")
        String modelName,
        @Schema(description = "顾问表达视角", example = "buffett_framework")
        String advisorStyle,
        @Schema(description = "AI 返回内容", example = "比亚迪具备较强的产业链优势，适合纳入长期观察名单。")
        String content,
        @Schema(description = "响应时间")
        Instant timestamp
) {
}
