package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "统一错误响应")
public record ErrorResponse(
        @Schema(description = "错误时间")
        Instant timestamp,
        @Schema(description = "HTTP 状态码", example = "400")
        int status,
        @Schema(description = "错误类型", example = "Bad Request")
        String error,
        @Schema(description = "错误信息", example = "请求参数校验失败")
        String message,
        @Schema(description = "请求路径", example = "/api/fund/chat")
        String path,
        @Schema(description = "字段级错误详情")
        Map<String, String> details
) {
}
