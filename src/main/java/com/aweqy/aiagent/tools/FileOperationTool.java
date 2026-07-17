package com.aweqy.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 文件操作工具，统一读写共享工具工作区中的文本文件，方便 AI 自主生成脚本和中间产物。
 */
public class FileOperationTool {

    @Tool(
            name = "Inspect tool workspace",
            description = "Inspect the shared writable tool workspace before creating scripts, task files, or downloaded resources."
    )
    public String inspectWorkspace() {
        try {
            Path workspaceRoot = ToolWorkspaceSupport.getWorkspaceRoot();
            return """
                    status: SUCCESS
                    operation: inspectWorkspace
                    workspaceRoot: %s
                    projectRoot: %s
                    notes:
                    - 共享工具工作区可用于写脚本、保存下载文件和任务中间产物。
                    - 文件工具只允许访问 workspaceRoot 下的路径。
                    - 终端工具默认在 projectRoot 执行，也支持切换到 workspaceRoot 的子目录执行。
                    """.formatted(
                    ToolWorkspaceSupport.toDisplayPath(workspaceRoot),
                    ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getProjectRoot())
            );
        } catch (Exception e) {
            return errorResult("inspectWorkspace", e);
        }
    }

    @Tool(
            name = "Read file from tool workspace",
            description = "Read a UTF-8 text file from the shared tool workspace. Supports nested relative paths such as scripts/task.ps1 or downloads/data.txt."
    )
    public String readFile(
            @ToolParam(description = "Relative file path inside the shared tool workspace, for example scripts/task.ps1")
            String relativePath) {
        try {
            Path targetPath = ToolWorkspaceSupport.resolveWorkspacePath(relativePath, false);
            if (!Files.exists(targetPath)) {
                return """
                        status: ERROR
                        operation: readFile
                        message: 文件不存在。
                        workspaceRoot: %s
                        requestedPath: %s
                        """.formatted(
                        ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot()),
                        relativePath
                );
            }
            if (Files.isDirectory(targetPath)) {
                return """
                        status: ERROR
                        operation: readFile
                        message: 目标路径是目录，不能按文本文件读取。
                        path: %s
                        """.formatted(ToolWorkspaceSupport.toDisplayPath(targetPath));
            }

            String content = Files.readString(targetPath, StandardCharsets.UTF_8);
            return """
                    status: SUCCESS
                    operation: readFile
                    workspaceRoot: %s
                    relativePath: %s
                    path: %s
                    sizeBytes: %d
                    lastModified: %s
                    content:
                    %s
                    """.formatted(
                    ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot()),
                    ToolWorkspaceSupport.toWorkspaceRelativePath(targetPath),
                    ToolWorkspaceSupport.toDisplayPath(targetPath),
                    Files.size(targetPath),
                    Instant.ofEpochMilli(Files.getLastModifiedTime(targetPath).toMillis()),
                    content
            );
        } catch (Exception e) {
            return errorResult("readFile", e);
        }
    }

    @Tool(
            name = "Write file to tool workspace",
            description = "Create or overwrite a UTF-8 text file in the shared tool workspace. Parent directories are created automatically. Use this to write scripts, configs, prompts, or data files."
    )
    public String writeFile(
            @ToolParam(description = "Relative file path inside the shared tool workspace")
            String relativePath,
            @ToolParam(description = "UTF-8 text content to write")
            String content) {
        return writeInternal("writeFile", relativePath, content, false);
    }

    @Tool(
            name = "Append file in tool workspace",
            description = "Append UTF-8 text to a file in the shared tool workspace. Creates the file and parent directories when they do not exist."
    )
    public String appendFile(
            @ToolParam(description = "Relative file path inside the shared tool workspace")
            String relativePath,
            @ToolParam(description = "UTF-8 text content to append")
            String content) {
        return writeInternal("appendFile", relativePath, content, true);
    }

    @Tool(
            name = "Create directory in tool workspace",
            description = "Create a directory inside the shared tool workspace before writing scripts or downloading files into it."
    )
    public String createDirectory(
            @ToolParam(description = "Relative directory path inside the shared tool workspace, for example scripts or runs/task-001")
            String relativeDirectory) {
        try {
            Path directoryPath = ToolWorkspaceSupport.resolveWorkspacePath(relativeDirectory, false);
            Files.createDirectories(directoryPath);
            return """
                    status: SUCCESS
                    operation: createDirectory
                    workspaceRoot: %s
                    relativePath: %s
                    path: %s
                    """.formatted(
                    ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot()),
                    ToolWorkspaceSupport.toWorkspaceRelativePath(directoryPath),
                    ToolWorkspaceSupport.toDisplayPath(directoryPath)
            );
        } catch (Exception e) {
            return errorResult("createDirectory", e);
        }
    }

    @Tool(
            name = "List files in tool workspace",
            description = "List files and directories under the shared tool workspace. Use this to inspect generated scripts, downloaded resources, and task outputs."
    )
    public String listFiles(
            @ToolParam(description = "Relative directory path inside the shared tool workspace. Use . or an empty value to inspect the workspace root")
            String relativeDirectory) {
        try {
            Path directoryPath = ".".equals(relativeDirectory)
                    ? ToolWorkspaceSupport.resolveWorkspacePath(null, true)
                    : ToolWorkspaceSupport.resolveWorkspacePath(relativeDirectory, true);
            if (!Files.exists(directoryPath)) {
                return """
                        status: ERROR
                        operation: listFiles
                        message: 目录不存在。
                        workspaceRoot: %s
                        requestedPath: %s
                        """.formatted(
                        ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot()),
                        relativeDirectory
                );
            }
            if (!Files.isDirectory(directoryPath)) {
                return """
                        status: ERROR
                        operation: listFiles
                        message: 目标路径不是目录。
                        path: %s
                        """.formatted(ToolWorkspaceSupport.toDisplayPath(directoryPath));
            }

            StringBuilder result = new StringBuilder();
            result.append("status: SUCCESS\n");
            result.append("operation: listFiles\n");
            result.append("workspaceRoot: ").append(ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot())).append('\n');
            result.append("relativePath: ").append(ToolWorkspaceSupport.toWorkspaceRelativePath(directoryPath)).append('\n');
            result.append("path: ").append(ToolWorkspaceSupport.toDisplayPath(directoryPath)).append('\n');
            result.append("entries:\n");

            try (Stream<Path> pathStream = Files.list(directoryPath).sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))) {
                pathStream.forEach(path -> {
                    try {
                        String type = Files.isDirectory(path) ? "DIR" : "FILE";
                        result.append("- ")
                                .append(type)
                                .append(" | ")
                                .append(ToolWorkspaceSupport.toWorkspaceRelativePath(path));
                        if (Files.isRegularFile(path)) {
                            result.append(" | ").append(Files.size(path)).append(" bytes");
                        }
                        result.append('\n');
                    } catch (IOException ex) {
                        result.append("- ERROR | ").append(path.getFileName()).append(" | ").append(ex.getMessage()).append('\n');
                    }
                });
            }
            return result.toString().trim();
        } catch (Exception e) {
            return errorResult("listFiles", e);
        }
    }

    private String writeInternal(String operation, String relativePath, String content, boolean append) {
        try {
            Path targetPath = ToolWorkspaceSupport.resolveWorkspacePath(relativePath, false);
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            boolean existedBefore = Files.exists(targetPath);
            if (append) {
                Files.writeString(
                        targetPath,
                        content == null ? "" : content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } else {
                Files.writeString(
                        targetPath,
                        content == null ? "" : content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            }

            return """
                    status: SUCCESS
                    operation: %s
                    workspaceRoot: %s
                    relativePath: %s
                    path: %s
                    existedBefore: %s
                    sizeBytes: %d
                    lastModified: %s
                    """.formatted(
                    operation,
                    ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot()),
                    ToolWorkspaceSupport.toWorkspaceRelativePath(targetPath),
                    ToolWorkspaceSupport.toDisplayPath(targetPath),
                    existedBefore,
                    Files.size(targetPath),
                    Instant.ofEpochMilli(Files.getLastModifiedTime(targetPath).toMillis())
            );
        } catch (Exception e) {
            return errorResult(operation, e);
        }
    }

    private String errorResult(String operation, Exception exception) {
        return """
                status: ERROR
                operation: %s
                workspaceRoot: %s
                message: %s
                """.formatted(
                operation,
                safeWorkspaceRoot(),
                exception.getMessage()
        );
    }

    private String safeWorkspaceRoot() {
        try {
            return ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot());
        } catch (IOException ignored) {
            return "unavailable";
        }
    }
}
