package com.aweqy.aiagent.service;

import com.aweqy.aiagent.tools.PDFGenerationTool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class ReportDocumentService {

    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PDFGenerationTool pdfGenerationTool;

    public ReportDocumentService(PDFGenerationTool pdfGenerationTool) {
        this.pdfGenerationTool = pdfGenerationTool;
    }

    public GeneratedReportDocument generateReportPdf(String chatId, String title, String reportContent) {
        String storageFileName = buildStorageFileName(chatId);
        String displayFileName = buildDisplayFileName(title);
        String pdfContent = buildPdfContent(chatId, title, reportContent);
        PDFGenerationTool.GeneratedPdfFile generatedPdfFile = pdfGenerationTool.generatePdfFile(storageFileName, pdfContent);
        return new GeneratedReportDocument(
                displayFileName,
                "/fund/reports/" + generatedPdfFile.storageFileName() + "/download"
        );
    }

    public GeneratedReportDocument generateReportPdf(String chatId, String title, List<String> suggestions) {
        return generateReportPdf(chatId, title, buildSuggestionContent(suggestions));
    }

    public Path resolveExistingPdf(String storageFileName) {
        Path pdfPath = pdfGenerationTool.resolvePdfPath(storageFileName);
        if (!Files.exists(pdfPath) || !Files.isRegularFile(pdfPath)) {
            return null;
        }
        return pdfPath;
    }

    private String buildStorageFileName(String chatId) {
        String normalizedChatId = StringUtils.hasText(chatId)
                ? chatId.trim().replaceAll("[^A-Za-z0-9_-]+", "-")
                : "report";
        String timestamp = FILE_TIME_FORMATTER.format(ZonedDateTime.now(SHANGHAI_ZONE));
        return "report-" + normalizedChatId + "-" + timestamp + ".pdf";
    }

    private String buildDisplayFileName(String title) {
        String normalizedTitle = StringUtils.hasText(title) ? title.trim() : "结构化投资报告";
        normalizedTitle = normalizedTitle
                .replace('\\', '-')
                .replace('/', '-')
                .replace(':', '-')
                .replace('*', '-')
                .replace('?', '-')
                .replace('"', '-')
                .replace('<', '-')
                .replace('>', '-')
                .replace('|', '-')
                .replaceAll("\\s+", " ");
        if (!normalizedTitle.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            normalizedTitle = normalizedTitle + ".pdf";
        }
        return normalizedTitle;
    }

    private String buildPdfContent(String chatId, String title, String reportContent) {
        StringBuilder builder = new StringBuilder();
        builder.append(StringUtils.hasText(title) ? title.trim() : "结构化投资报告").append("\n\n")
                .append("生成时间：").append(DISPLAY_TIME_FORMATTER.format(ZonedDateTime.now(SHANGHAI_ZONE))).append('\n');
        if (StringUtils.hasText(chatId)) {
            builder.append("会话 ID：").append(chatId.trim()).append('\n');
        }
        builder.append('\n');

        if (!StringUtils.hasText(reportContent)) {
            builder.append("暂无报告正文内容。");
            return builder.toString();
        }

        builder.append(reportContent.trim());
        return builder.toString().trim();
    }

    private String buildSuggestionContent(List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "1. 暂无具体建议条目。";
        }

        StringBuilder builder = new StringBuilder("结构化建议：\n");
        for (int index = 0; index < suggestions.size(); index++) {
            String item = suggestions.get(index);
            builder.append(index + 1)
                    .append(". ")
                    .append(StringUtils.hasText(item) ? item.trim() : "暂无内容")
                    .append('\n');
        }
        return builder.toString().trim();
    }

    public record GeneratedReportDocument(String displayFileName, String downloadPath) {
    }
}
