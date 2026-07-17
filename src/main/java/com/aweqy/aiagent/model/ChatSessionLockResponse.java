package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "会话占用锁响应")
public record ChatSessionLockResponse(
        @Schema(description = "会话 ID", example = "fund_abcd1234")
        String chatId,

        @Schema(description = "客户端标识", example = "web_client_ab12cd34")
        String clientId,

        @Schema(description = "租约令牌", example = "3f9b8d58-2c51-49a4-9f08-8d9717f6b1c1")
        String leaseToken,

        @Schema(description = "首次获取时间")
        Instant acquiredAt,

        @Schema(description = "本次租约过期时间")
        Instant expiresAt,

        @Schema(description = "租约有效期秒数", example = "30")
        long leaseTtlSeconds
) {
}
