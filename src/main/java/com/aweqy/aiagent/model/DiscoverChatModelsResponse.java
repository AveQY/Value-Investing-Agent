package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "远端模型列表响应")
public record DiscoverChatModelsResponse(
        List<String> modelIds,
        Instant timestamp
) {
}
