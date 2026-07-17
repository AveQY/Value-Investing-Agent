package com.aweqy.aiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.aweqy.aiagent.constant.FileConstant;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * PDF 生成工具类，提供将文本内容生成 PDF 文件的功能
 */
@Component
public class PDFGenerationTool {

    private static final List<String> FALLBACK_FONT_PATHS = List.of(
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/simkai.ttf",
            "C:/Windows/Fonts/simsunb.ttf",
            "C:/Windows/Fonts/msyh.ttc,0",
            "C:/Windows/Fonts/simsun.ttc,0"
    );

    @Tool(description = "Generate a PDF file with given content")
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        try {
            GeneratedPdfFile generatedPdfFile = generatePdfFile(fileName, content);
            return "PDF generated successfully to: " + generatedPdfFile.path();
        } catch (Exception e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }

    public GeneratedPdfFile generatePdfFile(String fileName, String content) {
        String normalizedFileName = normalizeFileName(fileName);
        Path filePath = resolvePdfPath(normalizedFileName);
        try {
            FileUtil.mkdir(filePath.getParent().toFile());
            try (PdfWriter writer = new PdfWriter(filePath.toString());
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                PdfFont font = loadPdfFont();
                document.setFont(font);
                Paragraph paragraph = new Paragraph(StringUtils.hasText(content) ? content : " ").setFont(font);
                document.add(paragraph);
            }
            if (!Files.exists(filePath) || Files.size(filePath) <= 0) {
                throw new IOException("生成的 PDF 为空文件。");
            }
            return new GeneratedPdfFile(normalizedFileName, filePath);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException ignored) {
                // Ignore cleanup failures and preserve the original exception.
            }
            throw new IllegalStateException("生成 PDF 失败：" + e.getMessage(), e);
        }
    }

    public Path resolvePdfPath(String fileName) {
        Path pdfDir = Path.of(FileConstant.FILE_SAVE_DIR, "pdf").toAbsolutePath().normalize();
        Path resolvedPath = pdfDir.resolve(normalizeFileName(fileName)).normalize();
        if (!resolvedPath.startsWith(pdfDir)) {
            throw new IllegalArgumentException("非法的 PDF 文件路径。");
        }
        return resolvedPath;
    }

    private String normalizeFileName(String fileName) {
        String normalized = StringUtils.hasText(fileName) ? fileName.trim() : "report";
        normalized = normalized
                .replace('\\', '-')
                .replace('/', '-')
                .replace(':', '-')
                .replace('*', '-')
                .replace('?', '-')
                .replace('"', '-')
                .replace('<', '-')
                .replace('>', '-')
                .replace('|', '-')
                .replaceAll("\\s+", "-");
        if (!normalized.toLowerCase().endsWith(".pdf")) {
            normalized = normalized + ".pdf";
        }
        return normalized;
    }

    private PdfFont loadPdfFont() throws IOException {
        Exception lastFailure = null;

        for (String candidate : FALLBACK_FONT_PATHS) {
            String fontPath = candidate.contains(",") ? candidate.substring(0, candidate.indexOf(',')) : candidate;
            if (!Files.exists(Path.of(fontPath))) {
                continue;
            }
            try {
                return PdfFontFactory.createFont(candidate, PdfEncodings.IDENTITY_H);
            } catch (Exception ex) {
                lastFailure = ex;
            }
        }

        try {
            return PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
        } catch (Exception ex) {
            lastFailure = ex;
        }

        if (lastFailure instanceof IOException ioException) {
            throw ioException;
        }
        throw new IOException("未找到可用的中文 PDF 字体。", lastFailure);
    }

    public record GeneratedPdfFile(String storageFileName, Path path) {
    }
}
