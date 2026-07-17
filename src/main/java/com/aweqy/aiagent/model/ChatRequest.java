package com.aweqy.aiagent.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "聊天请求")
public record ChatRequest(
        @Schema(description = "会话 ID，仅允许字母、数字、下划线和短横线", example = "chat-001")
        @NotBlank(message = "chatId 不能为空")
        @Size(max = 64, message = "chatId 长度不能超过 64")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "chatId 只能包含字母、数字、下划线和短横线")
        String chatId,

        @Schema(description = "用户输入内容", example = "请帮我分析比亚迪是否值得长期投资")
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 长度不能超过 4000")
        String message,

        @Schema(description = "用户名，仅在报告生成接口中使用", example = "张三")
        @Size(max = 50, message = "userName 长度不能超过 50")
        String userName,

        @Schema(description = "前端选择的模型配置 ID", example = "deepseek-r1")
        @Size(max = 80, message = "modelId 长度不能超过 80")
        @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "modelId 只能包含字母、数字、点、下划线和短横线")
        String modelId,

        @Schema(description = "顾问表达视角，可选值：default、buffett_framework、buffett_persona", example = "buffett_framework")
        @Pattern(regexp = "^(default|buffett_framework|buffett_persona)?$", message = "advisorStyle 仅支持 default、buffett_framework、buffett_persona")
        String advisorStyle,

        @Schema(description = "生成报告时用于确定首轮回答链路的模式，可选值：chat、rag、tools、value", example = "value")
        @Pattern(regexp = "^(chat|rag|tools|value)?$", message = "mode 仅支持 chat、rag、tools、value")
        String mode,

        @Schema(description = "报告生成时可选传入的 AI 回答原文，用于二次润色成 PDF 报告", example = "贵州茅台最近5年扣非ROE整体稳定在较高水平，但估值仍需结合安全边际判断。")
        @Size(max = 20000, message = "reportSourceContent 长度不能超过 20000")
        String reportSourceContent
) {
}
