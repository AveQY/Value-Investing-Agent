package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "报告任务状态响应")
public record ReportTaskResponse(
        @Schema(description = "报告任务 ID", example = "report-task-001")
        String taskId,
        @Schema(description = "会话 ID", example = "chat-001")
        String chatId,
        @Schema(description = "任务状态", example = "PROCESSING")
        String status,
        @Schema(description = "报告标题")
        String title,
        @Schema(description = "投资建议列表")
        List<String> suggestions,
        @Schema(description = "生成后的 PDF 文件名")
        String reportFileName,
        @Schema(description = "PDF 下载路径")
        String reportDownloadPath,
        @Schema(description = "失败原因")
        String error,
        @Schema(description = "更新时间")
        Instant timestamp
) {
}
