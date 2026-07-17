package com.aweqy.aiagent.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileOperationToolTest {

    @Test
    void readWriteAppendAndListFiles() throws Exception {
        FileOperationTool tool = new FileOperationTool();
        String baseDir = "tests/file-tool-" + UUID.randomUUID();
        String relativeFile = baseDir + "/scripts/sample.txt";

        String createDirectoryResult = tool.createDirectory(baseDir + "/scripts");
        assertTrue(createDirectoryResult.contains("status: SUCCESS"));

        String writeResult = tool.writeFile(relativeFile, "hello");
        assertTrue(writeResult.contains("status: SUCCESS"));

        String appendResult = tool.appendFile(relativeFile, "\nworld");
        assertTrue(appendResult.contains("status: SUCCESS"));

        String readResult = tool.readFile(relativeFile);
        assertTrue(readResult.contains("hello"));
        assertTrue(readResult.contains("world"));

        String listResult = tool.listFiles(baseDir + "/scripts");
        assertTrue(listResult.contains("sample.txt"));

        Path targetPath = ToolWorkspaceSupport.resolveWorkspacePath(relativeFile, false);
        String fileContent = Files.readString(targetPath, StandardCharsets.UTF_8);
        assertEquals("hello\nworld", fileContent);
    }
}
