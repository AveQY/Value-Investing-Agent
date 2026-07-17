package com.aweqy.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 终端执行工具，允许 AI 在受控目录内执行命令，适合串联脚本编写、下载和本地任务处理。
 */
public class TerminalOperationTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 600;

    @Tool(
            name = "Execute terminal command",
            description = "Execute a terminal command inside the project directory or the shared tool workspace. Use this only when the user explicitly asks to run local tasks, scripts, builds, conversions, or file-processing commands."
    )
    public String executeTerminalCommand(
            @ToolParam(description = "Exact terminal command to execute")
            String command,
            @ToolParam(description = "Optional working directory. Use . for the project root, or a relative/absolute path under the project root or shared tool workspace.")
            String workingDirectory,
            @ToolParam(description = "Optional timeout in seconds. Use 120 when unsure. Valid range is 5 to 600.")
            Integer timeoutSeconds,
            @ToolParam(description = "Optional shell preference: auto, powershell, cmd, sh, or bash. Use auto when unsure.")
            String preferredShell) {
        if (!StringUtils.hasText(command)) {
            return """
                    status: ERROR
                    operation: executeTerminalCommand
                    message: command 不能为空。
                    """;
        }

        try {
            Path executionDirectory = ToolWorkspaceSupport.resolveExecutionDirectory(workingDirectory);
            if (!Files.exists(executionDirectory) || !Files.isDirectory(executionDirectory)) {
                return """
                        status: ERROR
                        operation: executeTerminalCommand
                        message: workingDirectory 不存在或不是目录。
                        workingDirectory: %s
                        """.formatted(ToolWorkspaceSupport.toDisplayPath(executionDirectory));
            }

            int effectiveTimeout = sanitizeTimeout(timeoutSeconds);
            ShellCommand shellCommand = resolveShellCommand(command, preferredShell);

            ProcessBuilder processBuilder = new ProcessBuilder(shellCommand.commandParts());
            processBuilder.directory(executionDirectory.toFile());
            Process process = processBuilder.start();

            CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            boolean timedOut = !finished;
            if (timedOut) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();
            int exitCode = timedOut ? -1 : process.exitValue();
            String status = timedOut ? "TIMEOUT" : exitCode == 0 ? "SUCCESS" : "FAILED";

            return """
                    status: %s
                    operation: executeTerminalCommand
                    shell: %s
                    workingDirectory: %s
                    timeoutSeconds: %d
                    exitCode: %d
                    timedOut: %s
                    stdout:
                    %s
                    stderr:
                    %s
                    """.formatted(
                    status,
                    shellCommand.name(),
                    ToolWorkspaceSupport.toDisplayPath(executionDirectory),
                    effectiveTimeout,
                    exitCode,
                    timedOut,
                    stdout.isBlank() ? "(empty)" : stdout,
                    stderr.isBlank() ? "(empty)" : stderr
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return """
                    status: ERROR
                    operation: executeTerminalCommand
                    message: 命令执行被中断。
                    """;
        } catch (Exception e) {
            return """
                    status: ERROR
                    operation: executeTerminalCommand
                    message: %s
                    """.formatted(e.getMessage());
        }
    }

    private CompletableFuture<String> readStreamAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream stream = inputStream;
                 InputStreamReader inputStreamReader = new InputStreamReader(stream, StandardCharsets.UTF_8);
                 BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    output.append(line).append('\n');
                }
                return output.toString().trim();
            } catch (IOException e) {
                return "STREAM_READ_ERROR: " + e.getMessage();
            }
        });
    }

    private int sanitizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS));
    }

    private ShellCommand resolveShellCommand(String command, String preferredShell) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String shell = StringUtils.hasText(preferredShell)
                ? preferredShell.trim().toLowerCase(Locale.ROOT)
                : "auto";

        return switch (shell) {
            case "powershell" -> windows
                    ? new ShellCommand("powershell", List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command))
                    : new ShellCommand("pwsh", List.of("pwsh", "-NoProfile", "-Command", command));
            case "cmd" -> windows
                    ? new ShellCommand("cmd", List.of("cmd.exe", "/c", command))
                    : new ShellCommand("sh", List.of("/bin/sh", "-lc", command));
            case "sh", "bash" -> windows
                    ? new ShellCommand("powershell", List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command))
                    : new ShellCommand("sh", List.of("/bin/sh", "-lc", command));
            case "auto" -> windows
                    ? new ShellCommand("powershell", List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command))
                    : new ShellCommand("sh", List.of("/bin/sh", "-lc", command));
            default -> windows
                    ? new ShellCommand("powershell", List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command))
                    : new ShellCommand("sh", List.of("/bin/sh", "-lc", command));
        };
    }

    private record ShellCommand(String name, List<String> commandParts) {
    }
}
