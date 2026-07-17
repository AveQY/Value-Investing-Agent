package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "获取会话占用锁请求")
public record AcquireChatSessionLockRequest(
        @Schema(description = "客户端标识，建议同一浏览器标签页内保持稳定", example = "web_client_ab12cd34")
        @NotBlank(message = "clientId 不能为空")
        @Size(max = 120, message = "clientId 长度不能超过 120")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "clientId 只能包含字母、数字、下划线和短横线")
        String clientId
) {
}
