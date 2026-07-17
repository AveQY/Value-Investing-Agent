package com.aweqy.aiagent.exception;

public class ChatHistoryNotFoundException extends RuntimeException {

    public ChatHistoryNotFoundException(String chatId) {
        super("未找到 chatId=%s 对应的聊天历史".formatted(chatId));
    }
}
