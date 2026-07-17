package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "会话列表摘要")
public record ChatSessionSummary(
        @Schema(description = "会话 ID", example = "fund-mo9qbo5u-fod34r")
        String chatId,
        @Schema(description = "会话标题，默认取第一条用户问题", example = "请分析中国卫星最近5年现金及其等价物/带息债务")
        String preview,
        @Schema(description = "最近更新时间")
        Instant lastUpdatedAt,
        @Schema(description = "消息总数", example = "8")
        int messageCount
) {
}
