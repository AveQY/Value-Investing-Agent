package com.aweqy.aiagent.config;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(@Value("${app.chat.memory.dir:tmp/chat-memory}") String memoryDir) {
        return new FileBasedChatMemory(memoryDir);
    }
}
