package com.aweqy.aiagent.tools;

import com.aweqy.aiagent.constant.FileConstant;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 为具备执行能力的工具提供共享工作区和路径校验能力。
 */
final class ToolWorkspaceSupport {

    private static final Path PROJECT_ROOT = Paths.get(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();

    private static final Path WORKSPACE_ROOT = Paths.get(FileConstant.FILE_SAVE_DIR, "agent-workspace")
            .toAbsolutePath()
            .normalize();

    private ToolWorkspaceSupport() {
    }

    static Path getProjectRoot() {
        return PROJECT_ROOT;
    }

    static Path getWorkspaceRoot() throws IOException {
        Files.createDirectories(WORKSPACE_ROOT);
        return WORKSPACE_ROOT;
    }

    static Path resolveWorkspacePath(String relativePath, boolean allowRoot) throws IOException {
        Path root = getWorkspaceRoot();
        if (!StringUtils.hasText(relativePath)) {
            if (allowRoot) {
                return root;
            }
            throw new IllegalArgumentException("relativePath 不能为空，且必须位于共享工具工作区内。");
        }

        Path candidate = root.resolve(relativePath.trim()).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("路径越界：只能访问共享工具工作区内的文件。");
        }
        return candidate;
    }

    static Path resolveExecutionDirectory(String requestedDirectory) throws IOException {
        if (!StringUtils.hasText(requestedDirectory) || ".".equals(requestedDirectory.trim())) {
            return PROJECT_ROOT;
        }

        Path rawPath = Paths.get(requestedDirectory.trim());
        Path candidate = rawPath.isAbsolute()
                ? rawPath.toAbsolutePath().normalize()
                : PROJECT_ROOT.resolve(rawPath).normalize();

        Path workspaceRoot = getWorkspaceRoot();
        if (!candidate.startsWith(PROJECT_ROOT) && !candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("workingDirectory 只能位于项目根目录或共享工具工作区内。");
        }
        return candidate;
    }

    static String toDisplayPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    static String toWorkspaceRelativePath(Path path) throws IOException {
        Path workspaceRoot = getWorkspaceRoot();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.equals(workspaceRoot)) {
            return ".";
        }
        if (normalizedPath.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/');
        }
        return toDisplayPath(normalizedPath);
    }
}
