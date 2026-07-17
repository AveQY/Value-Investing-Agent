package com.aweqy.aiagent.config;

import com.aweqy.aiagent.model.ChatRuntimeConfigResponse;
import com.aweqy.aiagent.model.ToolLoopLimit;

/**
 * 工具循环运行时配置
 */
public final class ToolLoopRuntimeConfig {

    public static final ToolLoopLimit TOOLS_MODE = new ToolLoopLimit(20, 0, 200);
    public static final ToolLoopLimit VALUE_ANALYSIS = new ToolLoopLimit(20, 0, 200);

    private ToolLoopRuntimeConfig() {
    }

    public static ChatRuntimeConfigResponse toResponse() {
        return new ChatRuntimeConfigResponse(TOOLS_MODE, VALUE_ANALYSIS);
    }
}
