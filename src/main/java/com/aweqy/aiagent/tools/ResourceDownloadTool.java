package com.aweqy.aiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 资源下载工具，支持将网络资源保存到共享工具工作区，方便 AI 后续继续读取或执行。
 */
public class ResourceDownloadTool {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    @Tool(
            name = "Download resource to tool workspace",
            description = "Download a resource from a URL into the shared tool workspace. Use this when the user explicitly needs a local file, script, archive, or reference asset saved for later reading or terminal execution."
    )
    public String downloadResource(
            @ToolParam(description = "HTTP or HTTPS URL to download")
            String url,
            @ToolParam(description = "Relative save path inside the shared tool workspace, for example downloads/report.json or scripts/helper.ps1")
            String savePath) {
        try {
            Path targetPath = ToolWorkspaceSupport.resolveWorkspacePath(savePath, false);
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "ai-agent-tool/1.0")
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int statusCode = response.statusCode();
            String contentType = response.headers().firstValue("Content-Type").orElse("unknown");

            if (statusCode < 200 || statusCode >= 300) {
                String normalizedContentType = contentType.toLowerCase();
                String responseBodyText = normalizedContentType.contains("text")
                        || normalizedContentType.contains("json")
                        || normalizedContentType.contains("xml")
                        ? new String(response.body(), StandardCharsets.UTF_8)
                        : "<non-text response body>";
                return """
                        status: ERROR
                        operation: downloadResource
                        url: %s
                        statusCode: %d
                        contentType: %s
                        message: 下载失败，HTTP 响应不是 2xx。
                        responseBody:
                        %s
                        """.formatted(url, statusCode, contentType, responseBodyText);
            }

            Files.write(targetPath, response.body());
            return """
                    status: SUCCESS
                    operation: downloadResource
                    workspaceRoot: %s
                    relativePath: %s
                    savedPath: %s
                    url: %s
                    statusCode: %d
                    contentType: %s
                    sizeBytes: %d
                    """.formatted(
                    ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot()),
                    ToolWorkspaceSupport.toWorkspaceRelativePath(targetPath),
                    ToolWorkspaceSupport.toDisplayPath(targetPath),
                    url,
                    statusCode,
                    contentType,
                    Files.size(targetPath)
            );
        } catch (Exception e) {
            return """
                    status: ERROR
                    operation: downloadResource
                    workspaceRoot: %s
                    message: %s
                    """.formatted(safeWorkspaceRoot(), e.getMessage());
        }
    }

    private String safeWorkspaceRoot() {
        try {
            return ToolWorkspaceSupport.toDisplayPath(ToolWorkspaceSupport.getWorkspaceRoot());
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
}
