package com.aweqy.aiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Spring AI 框架调用 OpenAI 兼容大模型
 */
// 取消注释后，项目启动时会执行
@Component
@ConditionalOnProperty(name = "app.demo.invoke.enabled", havingValue = "true")
public class SpringAiAiInvoke implements CommandLineRunner {

    @Resource(name = "openAiChatModel")
    private ChatModel openAiChatModel;

    @Override
    public void run(String... args) throws Exception {
        AssistantMessage assistantMessage = openAiChatModel.call(new Prompt("你好"))
                .getResult()
                .getOutput();
        System.out.println(assistantMessage.getText());
    }
}
