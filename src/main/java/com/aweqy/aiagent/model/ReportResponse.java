package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "结构化报告响应")
public record ReportResponse(
        @Schema(description = "会话 ID", example = "chat-001")
        String chatId,
        @Schema(description = "报告标题", example = "张三的数据报告")
        String title,
        @Schema(description = "投资建议列表")
        List<String> suggestions,
        @Schema(description = "生成后的 PDF 文件名", example = "张三的数据报告.pdf")
        String reportFileName,
        @Schema(description = "PDF 下载路径", example = "/fund/reports/report-chat-001-20260422153000123.pdf/download")
        String reportDownloadPath,
        @Schema(description = "响应时间")
        Instant timestamp
) {
}
