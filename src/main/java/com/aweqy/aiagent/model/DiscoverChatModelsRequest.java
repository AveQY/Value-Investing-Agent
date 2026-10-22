package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "获取远端模型列表请求")
public record DiscoverChatModelsRequest(
        @Schema(description = "OpenAI 兼容基础地址", example = "https://api.example.com/v1")
        @NotBlank(message = "baseUrl 不能为空")
        @Size(max = 255, message = "baseUrl 长度不能超过 255")
        @Pattern(regexp = "^https?://.+$", message = "baseUrl 必须是 http 或 https 地址")
        String baseUrl,

        @Schema(description = "API Key", example = "sk-xxx")
        @NotBlank(message = "apiKey 不能为空")
        @Size(max = 512, message = "apiKey 长度不能超过 512")
        String apiKey
) {
}
