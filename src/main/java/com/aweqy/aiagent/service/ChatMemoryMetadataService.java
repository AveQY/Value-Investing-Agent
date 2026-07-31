package com.aweqy.aiagent.service;

import com.aweqy.aiagent.chatmemory.FileBasedChatMemory;
import com.aweqy.aiagent.model.ChatTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatMemoryMetadataService {

    public static final String DISPLAY_CONTENT_KEY = "displayContent";
    public static final String TRACE_KEY = "trace";
    public static final String MESSAGE_KIND_KEY = "messageKind";
    public static final String REPORT_KIND = "report";
    public static final String REPORT_TITLE_KEY = "reportTitle";
    public static final String REPORT_FILE_NAME_KEY = "reportFileName";
    public static final String REPORT_DOWNLOAD_PATH_KEY = "reportDownloadPath";
    public static final String REPORT_STATUS_KEY = "reportStatus";
    public static final String REPORT_TASK_ID_KEY = "reportTaskId";
    public static final String REPORT_ERROR_KEY = "reportError";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_INTERVAL_MILLIS = 120L;

    private final ChatMemory chatMemory;
    private final FileBasedChatMemory fileBasedChatMemory;

    public ChatMemoryMetadataService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.fileBasedChatMemory = chatMemory instanceof FileBasedChatMemory fileBased
                ? fileBased
                : null;
    }

    public void recordExchange(String chatId, String userMessage, String assistantReply, ChatTrace trace) {
        recordExchange(chatId, userMessage, assistantReply, trace, null, null, null, null, null, null, null);
    }

    public void recordReportExchange(String chatId,
                                     String userMessage,
                                     String assistantReply,
                                     String reportTitle,
                                     String reportFileName,
                                     String reportDownloadPath) {
        recordExchange(
                chatId,
                userMessage,
                assistantReply,
                null,
                REPORT_KIND,
                reportTitle,
                reportFileName,
                reportDownloadPath,
                null,
                null,
                null
        );
    }

    public void recordAssistantReport(String chatId,
                                      String assistantReply,
                                      String reportTitle,
                                      String reportFileName,
                                      String reportDownloadPath) {
        recordAssistantReport(chatId, assistantReply, reportTitle, reportFileName, reportDownloadPath, null, null, null);
    }

    public void recordAssistantReport(String chatId,
                                      String assistantReply,
                                      String reportTitle,
                                      String reportFileName,
                                      String reportDownloadPath,
                                      String reportStatus,
                                      String reportTaskId,
                                      String reportError) {
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(assistantReply)) {
            return;
        }

        chatMemory.add(chatId, List.of(
                new AssistantMessage(
                        assistantReply,
                        buildAssistantMetadata(null, REPORT_KIND, reportTitle, reportFileName, reportDownloadPath, reportStatus, reportTaskId, reportError)
                )
        ));
    }

    private void recordExchange(String chatId,
                                String userMessage,
                                String assistantReply,
                                ChatTrace trace,
                                String messageKind,
                                String reportTitle,
                                String reportFileName,
                                String reportDownloadPath,
                                String reportStatus,
                                String reportTaskId,
                                String reportError) {
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(assistantReply)) {
            return;
        }

        chatMemory.add(chatId, List.of(
                new UserMessage(
                        userMessage == null ? "" : userMessage,
                        List.of(),
                        buildUserMetadata(userMessage)
                ),
                new AssistantMessage(
                        assistantReply,
                        buildAssistantMetadata(trace, messageKind, reportTitle, reportFileName, reportDownloadPath, reportStatus, reportTaskId, reportError)
                )
        ));
    }

    public void enrichLatestExchange(String chatId, String userDisplayContent, ChatTrace trace) {
        enrichLatestExchange(chatId, userDisplayContent, trace, null, null, null, null, null, null, null);
    }

    public void enrichLatestReport(String chatId,
                                   String userDisplayContent,
                                   String reportTitle,
                                   String reportFileName,
                                   String reportDownloadPath) {
        enrichLatestReport(chatId, userDisplayContent, reportTitle, reportFileName, reportDownloadPath, null, null, null);
    }

    public void enrichLatestReport(String chatId,
                                   String userDisplayContent,
                                   String reportTitle,
                                   String reportFileName,
                                   String reportDownloadPath,
                                   String reportStatus,
                                   String reportTaskId,
                                   String reportError) {
        enrichLatestExchange(
                chatId,
                userDisplayContent,
                null,
                REPORT_KIND,
                reportTitle,
                reportFileName,
                reportDownloadPath,
                reportStatus,
                reportTaskId,
                reportError
        );
    }

    public void updateReportTaskRecord(String chatId,
                                        String reportTaskId,
                                        String reportTitle,
                                        String reportFileName,
                                        String reportDownloadPath,
                                        String reportStatus,
                                        String reportError) {
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(reportTaskId) || fileBasedChatMemory == null) {
            return;
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (updateReportTaskRecordOnce(
                    chatId,
                    reportTaskId,
                    reportTitle,
                    reportFileName,
                    reportDownloadPath,
                    reportStatus,
                    reportError
            )) {
                return;
            }
            sleepQuietly(RETRY_INTERVAL_MILLIS);
        }
    }

    private boolean updateReportTaskRecordOnce(String chatId,
                                                String reportTaskId,
                                                String reportTitle,
                                                String reportFileName,
                                                String reportDownloadPath,
                                                String reportStatus,
                                                String reportError) {
        if (!fileBasedChatMemory.exists(chatId)) {
            return false;
        }

        List<Message> messages = new ArrayList<>(fileBasedChatMemory.getAll(chatId));
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message.getMessageType() != MessageType.ASSISTANT
                    || !reportTaskId.equals(String.valueOf(safeMetadata(message).get(REPORT_TASK_ID_KEY)))) {
                continue;
            }

            messages.set(index, rebuildAssistantMessage(
                    message,
                    null,
                    REPORT_KIND,
                    reportTitle,
                    reportFileName,
                    reportDownloadPath,
                    reportStatus,
                    reportTaskId,
                    reportError
            ));
            fileBasedChatMemory.replaceAll(chatId, messages);
            return true;
        }
        return false;
    }

    private void enrichLatestExchange(String chatId,
                                      String userDisplayContent,
                                      ChatTrace trace,
                                      String messageKind,
                                      String reportTitle,
                                      String reportFileName,
                                      String reportDownloadPath,
                                      String reportStatus,
                                      String reportTaskId,
                                      String reportError) {
        if (!StringUtils.hasText(chatId) || fileBasedChatMemory == null) {
            return;
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (enrichLatestExchangeOnce(
                    chatId,
                    userDisplayContent,
                    trace,
                    messageKind,
                    reportTitle,
                    reportFileName,
                    reportDownloadPath,
                    reportStatus,
                    reportTaskId,
                    reportError
            )) {
                return;
            }
            sleepQuietly(RETRY_INTERVAL_MILLIS);
        }
    }

    public void enrichLatestExchangeAsync(String chatId, String userDisplayContent, ChatTrace trace) {
        CompletableFuture.runAsync(() -> enrichLatestExchange(chatId, userDisplayContent, trace));
    }

    private boolean enrichLatestExchangeOnce(String chatId,
                                             String userDisplayContent,
                                             ChatTrace trace,
                                             String messageKind,
                                             String reportTitle,
                                             String reportFileName,
                                             String reportDownloadPath,
                                             String reportStatus,
                                             String reportTaskId,
                                             String reportError) {
        if (!fileBasedChatMemory.exists(chatId)) {
            return false;
        }

        List<Message> messages = new ArrayList<>(fileBasedChatMemory.getAll(chatId));
        int assistantIndex = findLastMessageIndex(messages, MessageType.ASSISTANT);
        if (assistantIndex < 0) {
            return false;
        }

        int userIndex = findLastMessageIndexBefore(messages, MessageType.USER, assistantIndex);
        if (userIndex < 0) {
            return false;
        }

        messages.set(userIndex, rebuildUserMessage(messages.get(userIndex), userDisplayContent));
        messages.set(assistantIndex, rebuildAssistantMessage(
                messages.get(assistantIndex),
                trace,
                messageKind,
                reportTitle,
                reportFileName,
                reportDownloadPath,
                reportStatus,
                reportTaskId,
                reportError
        ));
        fileBasedChatMemory.replaceAll(chatId, messages);
        return true;
    }

    private int findLastMessageIndex(List<Message> messages, MessageType messageType) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).getMessageType() == messageType) {
                return index;
            }
        }
        return -1;
    }

    private int findLastMessageIndexBefore(List<Message> messages, MessageType messageType, int endExclusive) {
        for (int index = Math.min(endExclusive - 1, messages.size() - 1); index >= 0; index--) {
            if (messages.get(index).getMessageType() == messageType) {
                return index;
            }
        }
        return -1;
    }

    private Message rebuildUserMessage(Message message, String userDisplayContent) {
        if (!(message instanceof UserMessage userMessage)) {
            return message;
        }

        Map<String, Object> metadata = new LinkedHashMap<>(safeMetadata(userMessage));
        if (StringUtils.hasText(userDisplayContent)) {
            metadata.put(DISPLAY_CONTENT_KEY, userDisplayContent.trim());
        }

        return new UserMessage(
                userMessage.getText(),
                userMessage.getMedia(),
                metadata
        );
    }

    private Message rebuildAssistantMessage(Message message,
                                            ChatTrace trace,
                                            String messageKind,
                                            String reportTitle,
                                            String reportFileName,
                                            String reportDownloadPath,
                                            String reportStatus,
                                            String reportTaskId,
                                            String reportError) {
        if (!(message instanceof AssistantMessage assistantMessage)) {
            return message;
        }

        Map<String, Object> metadata = new LinkedHashMap<>(safeMetadata(assistantMessage));
        if (trace == null || trace.isEmpty()) {
            metadata.remove(TRACE_KEY);
        } else {
            metadata.put(TRACE_KEY, OBJECT_MAPPER.convertValue(trace, Map.class));
        }
        applyReportMetadata(metadata, messageKind, reportTitle, reportFileName, reportDownloadPath, reportStatus, reportTaskId, reportError);

        return new AssistantMessage(
                assistantMessage.getText(),
                metadata,
                assistantMessage.getToolCalls(),
                assistantMessage.getMedia()
        );
    }

    private Map<String, Object> buildUserMetadata(String userDisplayContent) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(userDisplayContent)) {
            metadata.put(DISPLAY_CONTENT_KEY, userDisplayContent.trim());
        }
        return metadata;
    }

    private Map<String, Object> buildAssistantMetadata(ChatTrace trace,
                                                       String messageKind,
                                                       String reportTitle,
                                                       String reportFileName,
                                                       String reportDownloadPath) {
        return buildAssistantMetadata(trace, messageKind, reportTitle, reportFileName, reportDownloadPath, null, null, null);
    }

    private Map<String, Object> buildAssistantMetadata(ChatTrace trace,
                                                       String messageKind,
                                                       String reportTitle,
                                                       String reportFileName,
                                                       String reportDownloadPath,
                                                       String reportStatus,
                                                       String reportTaskId,
                                                       String reportError) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (trace != null && !trace.isEmpty()) {
            metadata.put(TRACE_KEY, OBJECT_MAPPER.convertValue(trace, Map.class));
        }
        applyReportMetadata(metadata, messageKind, reportTitle, reportFileName, reportDownloadPath, reportStatus, reportTaskId, reportError);
        return metadata;
    }

    private void applyReportMetadata(Map<String, Object> metadata,
                                     String messageKind,
                                     String reportTitle,
                                     String reportFileName,
                                     String reportDownloadPath,
                                     String reportStatus,
                                     String reportTaskId,
                                     String reportError) {
        if (REPORT_KIND.equals(messageKind)) {
            metadata.put(MESSAGE_KIND_KEY, REPORT_KIND);
        } else {
            metadata.remove(MESSAGE_KIND_KEY);
        }
        putOrRemove(metadata, REPORT_TITLE_KEY, reportTitle);
        putOrRemove(metadata, REPORT_FILE_NAME_KEY, reportFileName);
        putOrRemove(metadata, REPORT_DOWNLOAD_PATH_KEY, reportDownloadPath);
        putOrRemove(metadata, REPORT_STATUS_KEY, reportStatus);
        putOrRemove(metadata, REPORT_TASK_ID_KEY, reportTaskId);
        putOrRemove(metadata, REPORT_ERROR_KEY, reportError);
    }

    private void putOrRemove(Map<String, Object> metadata, String key, String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value.trim());
            return;
        }
        metadata.remove(key);
    }

    private Map<String, Object> safeMetadata(Message message) {
        return message.getMetadata() == null ? Map.of() : message.getMetadata();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
