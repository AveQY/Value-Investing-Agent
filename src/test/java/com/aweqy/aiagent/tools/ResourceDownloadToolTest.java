package com.aweqy.aiagent.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceDownloadToolTest {

    @Test
    void downloadResourceToWorkspace() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/sample.txt", this::handleSampleResponse);
        server.start();

        try {
            ResourceDownloadTool tool = new ResourceDownloadTool();
            String relativePath = "tests/download-tool-" + UUID.randomUUID() + "/downloads/sample.txt";
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sample.txt";

            String result = tool.downloadResource(url, relativePath);
            assertTrue(result.contains("status: SUCCESS"));
            assertTrue(result.contains("sample.txt"));

            Path savedPath = ToolWorkspaceSupport.resolveWorkspacePath(relativePath, false);
            assertTrue(Files.exists(savedPath));
            assertEquals("downloaded-content", Files.readString(savedPath, StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    private void handleSampleResponse(HttpExchange exchange) throws IOException {
        byte[] responseBody = "downloaded-content".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, responseBody.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBody);
        } finally {
            exchange.close();
        }
    }
}
