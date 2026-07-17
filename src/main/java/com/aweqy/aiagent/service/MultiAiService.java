package com.aweqy.aiagent.service;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * 统一使用 OpenAI 兼容模型的服务
 */
@Service
public class MultiAiService {

    /**
     * OpenAI 模型
     */
    @Resource
    private ChatModel openAiChatModel;

    /**
     * 使用 OpenAI 进行对话
     *
     * @param message 用户消息
     * @return AI 回复
     */
    public String chatWithOpenAi(String message) {
        return openAiChatModel.call(new Prompt(message))
                .getResult()
                .getOutput()
                .getText();
    }

    /**
     * 根据提供商获取对应的 ChatModel
     *
     * @param provider 模型提供商
     * @return 对应的 ChatModel
     */
    public ChatModel getChatModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai", "gpt" -> openAiChatModel;
            default -> throw new IllegalArgumentException("未知的模型提供商: " + provider + "，当前仅支持: openai");
        };
    }

    /**
     * 使用指定提供商进行对话
     *
     * @param provider 模型提供商
     * @param message  用户消息
     * @return AI 回复
     */
    public String chat(String provider, String message) {
        ChatModel model = getChatModel(provider);
        return model.call(new Prompt(message))
                .getResult()
                .getOutput()
                .getText();
    }
}
