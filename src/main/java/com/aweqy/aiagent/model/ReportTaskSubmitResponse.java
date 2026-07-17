package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "提交报告任务响应")
public record ReportTaskSubmitResponse(
        @Schema(description = "报告任务 ID", example = "report-task-001")
        String taskId,
        @Schema(description = "会话 ID", example = "chat-001")
        String chatId,
        @Schema(description = "任务状态", example = "PROCESSING")
        String status,
        @Schema(description = "响应时间")
        Instant timestamp
) {
}
