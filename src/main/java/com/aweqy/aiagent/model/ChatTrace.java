package com.aweqy.aiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "回答过程追踪信息")
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatTrace(
        @Schema(description = "工具调用摘要")
        List<ChatTraceToolCall> toolCalls,
        @Schema(description = "RAG 知识库引用")
        List<ChatTraceReference> ragReferences
) {

    @JsonIgnore
    public boolean isEmpty() {
        return (toolCalls == null || toolCalls.isEmpty())
                && (ragReferences == null || ragReferences.isEmpty());
    }
}
