package com.aweqy.aiagent.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PDFGenerationToolTest {

    private final PDFGenerationTool tool = new PDFGenerationTool();

    @AfterEach
    void cleanup() throws Exception {
        Path pdfDir = Path.of(System.getProperty("user.dir"), "tmp", "pdf");
        if (Files.exists(pdfDir)) {
            try (var paths = Files.walk(pdfDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        });
            }
        }
    }

    @Test
    void generatePDF() throws Exception {
        String fileName = "亚迪报告.pdf";
        String content = "比亚迪报告测试";
        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
        assertTrue(result.contains("PDF generated successfully"));

        Path generatedFile = tool.resolvePdfPath(fileName);
        assertTrue(Files.exists(generatedFile));
        assertTrue(Files.size(generatedFile) > 0);
    }
}
