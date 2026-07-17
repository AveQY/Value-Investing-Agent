package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "单条聊天历史消息")
public record ChatHistoryMessage(
        @Schema(description = "消息角色", example = "USER")
        String role,
        @Schema(description = "消息内容", example = "请分析一下比亚迪是否适合长期投资")
        String content,
        @Schema(description = "回答过程追踪信息，仅 AI 回复可能存在")
        ChatTrace trace,
        @Schema(description = "消息类型，报告消息会返回 report", example = "report")
        String kind,
        @Schema(description = "报告标题", example = "宁德时代结构化投资报告")
        String title,
        @Schema(description = "报告 PDF 文件名", example = "宁德时代结构化投资报告.pdf")
        String reportFileName,
        @Schema(description = "报告 PDF 下载路径", example = "/fund/reports/report-chat-001-20260422153000123.pdf/download")
        String reportDownloadPath,
        @Schema(description = "报告任务状态", example = "PROCESSING")
        String reportStatus,
        @Schema(description = "报告任务 ID", example = "report-task-001")
        String reportTaskId,
        @Schema(description = "报告生成失败原因")
        String reportError
) {
}
