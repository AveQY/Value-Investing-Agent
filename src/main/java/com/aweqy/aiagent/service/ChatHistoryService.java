package com.aweqy.aiagent.service;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import com.aweqy.aiagent.exception.ChatHistoryNotFoundException;
import com.aweqy.aiagent.model.ChatHistoryMessage;
import com.aweqy.aiagent.model.ChatHistoryResponse;
import com.aweqy.aiagent.model.ChatSessionCatalogResponse;
import com.aweqy.aiagent.model.ChatSessionSummary;
import com.aweqy.aiagent.model.ChatTrace;
import com.aweqy.aiagent.model.ChatTraceReference;
import com.aweqy.aiagent.model.ChatTraceToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class ChatHistoryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final FileBasedChatMemory chatMemory;

    public ChatHistoryService(ChatMemory chatMemory) {
        if (!(chatMemory instanceof FileBasedChatMemory fileBasedChatMemory)) {
            throw new IllegalStateException("当前聊天记忆实现不支持历史记录管理");
        }
        this.chatMemory = fileBasedChatMemory;
    }

    public ChatHistoryResponse getHistory(String chatId) {
        if (!chatMemory.exists(chatId)) {
            throw new ChatHistoryNotFoundException(chatId);
        }
        List<ChatHistoryMessage> messages = chatMemory.getAll(chatId).stream()
                .map(this::toHistoryMessage)
                .toList();
        return new ChatHistoryResponse(chatId, messages.size(), messages, Instant.now());
    }

    public ChatSessionCatalogResponse listSessions() {
        List<ChatSessionSummary> sessions = chatMemory.listConversationFiles().stream()
                .map(this::toSessionSummary)
                .toList();
        return new ChatSessionCatalogResponse(sessions, Instant.now());
    }

    public void deleteHistory(String chatId) {
        if (!chatMemory.exists(chatId)) {
            throw new ChatHistoryNotFoundException(chatId);
        }
        chatMemory.clear(chatId);
    }

    private ChatHistoryMessage toHistoryMessage(Message message) {
        Map<String, Object> metadata = message.getMetadata() == null ? Map.of() : message.getMetadata();
        String content = resolveDisplayContent(message, metadata);
        return new ChatHistoryMessage(
                message.getMessageType().name(),
                content,
                resolveTrace(metadata),
                resolveStringMetadata(metadata, ChatMemoryMetadataService.MESSAGE_KIND_KEY),
                resolveStringMetadata(metadata, ChatMemoryMetadataService.REPORT_TITLE_KEY),
                resolveStringMetadata(metadata, ChatMemoryMetadataService.REPORT_FILE_NAME_KEY),
                resolveStringMetadata(metadata, ChatMemoryMetadataService.REPORT_DOWNLOAD_PATH_KEY),
                resolveStringMetadata(metadata, ChatMemoryMetadataService.REPORT_STATUS_KEY),
                resolveStringMetadata(metadata, ChatMemoryMetadataService.REPORT_TASK_ID_KEY),
                resolveStringMetadata(metadata, ChatMemoryMetadataService.REPORT_ERROR_KEY)
        );
    }

    private String resolveDisplayContent(Message message, Map<String, Object> metadata) {
        if (message.getMessageType() == MessageType.USER) {
            Object displayContent = metadata.get(ChatMemoryMetadataService.DISPLAY_CONTENT_KEY);
            if (displayContent instanceof String value && StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return message.getText();
    }

    private ChatSessionSummary toSessionSummary(FileBasedChatMemory.ConversationFile conversationFile) {
        List<ChatHistoryMessage> messages = chatMemory.getAll(conversationFile.conversationId()).stream()
                .map(this::toHistoryMessage)
                .toList();
        return new ChatSessionSummary(
                conversationFile.conversationId(),
                resolveSessionPreview(messages),
                conversationFile.lastModifiedAt(),
                messages.size()
        );
    }

    private String resolveSessionPreview(List<ChatHistoryMessage> messages) {
        for (ChatHistoryMessage message : messages) {
            if (message == null || !"USER".equals(message.role())) {
                continue;
            }
            String content = normalizePreviewText(message.content());
            if (StringUtils.hasText(content)) {
                return content;
            }
        }

        for (ChatHistoryMessage message : messages) {
            if (message == null) {
                continue;
            }

            String title = normalizePreviewText(message.title());
            if (StringUtils.hasText(title)) {
                return title;
            }

            String content = normalizePreviewText(message.content());
            if (StringUtils.hasText(content)) {
                return content;
            }
        }

        return "等待第一条问题";
    }

    private String normalizePreviewText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private ChatTrace resolveTrace(Map<String, Object> metadata) {
        Object rawTrace = metadata.get(ChatMemoryMetadataService.TRACE_KEY);
        if (rawTrace == null) {
            return null;
        }

        try {
            ChatTrace trace = OBJECT_MAPPER.convertValue(rawTrace, ChatTrace.class);
            if (trace == null || trace.isEmpty()) {
                return null;
            }

            List<ChatTraceToolCall> toolCalls = safeList(trace.toolCalls()).stream()
                    .map(this::normalizeToolCall)
                    .filter(item -> item != null && hasText(item.toolName(), item.outputPreview(), item.arguments()))
                    .toList();
            List<ChatTraceReference> ragReferences = safeList(trace.ragReferences()).stream()
                    .map(this::normalizeReference)
                    .filter(item -> item != null && hasText(item.source(), item.snippet()))
                    .toList();

            ChatTrace normalizedTrace = new ChatTrace(toolCalls, ragReferences);
            return normalizedTrace.isEmpty() ? null : normalizedTrace;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private ChatTraceToolCall normalizeToolCall(ChatTraceToolCall toolCall) {
        if (toolCall == null) {
            return null;
        }
        return new ChatTraceToolCall(
                trimToNull(toolCall.title()),
                trimToNull(toolCall.toolName()),
                trimToNull(toolCall.arguments()),
                trimToNull(toolCall.outputPreview())
        );
    }

    private ChatTraceReference normalizeReference(ChatTraceReference reference) {
        if (reference == null) {
            return null;
        }
        return new ChatTraceReference(
                trimToNull(reference.source()),
                trimToNull(reference.snippet())
        );
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String resolveStringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return null;
    }
}
