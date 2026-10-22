package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "新增聊天模型请求")
public record CreateChatModelRequest(
        @Schema(description = "展示名称（兼容旧客户端，服务端会以 modelId 为准）", example = "qwen/qwen3.5-397b-a17b")
        @Size(max = 120, message = "name 长度不能超过 120")
        String name,

        @Schema(description = "OpenAI 兼容基础地址", example = "https://integrate.api.nvidia.com/v1")
        @NotBlank(message = "baseUrl 不能为空")
        @Size(max = 255, message = "baseUrl 长度不能超过 255")
        @Pattern(regexp = "^https?://.+$", message = "baseUrl 必须是 http 或 https 地址")
        String baseUrl,

        @Schema(description = "API Key", example = "nvapi-xxx")
        @NotBlank(message = "apiKey 不能为空")
        @Size(max = 512, message = "apiKey 长度不能超过 512")
        String apiKey,

        @Schema(description = "远端模型 ID", example = "qwen/qwen3.5-397b-a17b")
        @NotBlank(message = "modelId 不能为空")
        @Size(max = 120, message = "modelId 长度不能超过 120")
        String modelId
) {
}
