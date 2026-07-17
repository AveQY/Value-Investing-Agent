package com.aweqy.aiagent.service;

import com.aweqy.aiagent.app.FundApp;
import com.aweqy.aiagent.model.ChatRequest;
import com.aweqy.aiagent.model.ReportTaskResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReportTaskService {

    private final FundApp fundApp;
    private final ChatModelRegistryService chatModelRegistryService;
    private final ChatMemoryMetadataService chatMemoryMetadataService;
    private final Map<String, ReportTaskRecord> tasks = new ConcurrentHashMap<>();

    public ReportTaskService(FundApp fundApp,
                             ChatModelRegistryService chatModelRegistryService,
                             ChatMemoryMetadataService chatMemoryMetadataService) {
        this.fundApp = fundApp;
        this.chatModelRegistryService = chatModelRegistryService;
        this.chatMemoryMetadataService = chatMemoryMetadataService;
    }

    public ReportTaskResponse createTask(ChatRequest request) {
        String taskId = "report-task-" + UUID.randomUUID();
        ReportTaskRecord record = new ReportTaskRecord(taskId, request.chatId(), "PROCESSING", null, List.of(), null, null, null, Instant.now());
        tasks.put(taskId, record);

        String placeholderTitle = "正在生成结构化投资报告";
        chatMemoryMetadataService.recordAssistantReport(
                request.chatId(),
                "报告生成中，请稍候刷新状态。",
                placeholderTitle,
                null,
                null,
                "PROCESSING",
                taskId,
                null
        );

        CompletableFuture.runAsync(() -> executeTask(record, request));
        return toResponse(record);
    }

    public ReportTaskResponse getTask(String taskId) {
        ReportTaskRecord record = tasks.get(taskId);
        if (record == null) {
            throw new IllegalArgumentException("报告任务不存在：" + taskId);
        }
        return toResponse(record);
    }

    private void executeTask(ReportTaskRecord record, ChatRequest request) {
        try {
            String userName = StringUtils.hasText(request.userName()) ? request.userName() : "用户";
            String modelId = chatModelRegistryService.resolveModelId(request.modelId());
            FundApp.FundReport report = fundApp.doChatWithReport(
                    request.message(),
                    request.chatId(),
                    userName,
                    request.advisorStyle(),
                    modelId,
                    request.reportSourceContent(),
                    request.mode()
            );
            ReportTaskRecord success = new ReportTaskRecord(
                    record.taskId(),
                    record.chatId(),
                    "SUCCESS",
                    report.title(),
                    report.suggestions(),
                    report.reportFileName(),
                    report.reportDownloadPath(),
                    null,
                    Instant.now()
            );
            tasks.put(record.taskId(), success);
            chatMemoryMetadataService.enrichLatestReport(
                    request.chatId(),
                    request.message(),
                    report.title(),
                    report.reportFileName(),
                    report.reportDownloadPath(),
                    "SUCCESS",
                    record.taskId(),
                    null
            );
        } catch (Exception ex) {
            ReportTaskRecord failed = new ReportTaskRecord(
                    record.taskId(),
                    record.chatId(),
                    "FAILED",
                    "报告生成失败",
                    List.of(),
                    null,
                    null,
                    ex.getMessage(),
                    Instant.now()
            );
            tasks.put(record.taskId(), failed);
            chatMemoryMetadataService.enrichLatestReport(
                    request.chatId(),
                    request.message(),
                    "报告生成失败",
                    null,
                    null,
                    "FAILED",
                    record.taskId(),
                    ex.getMessage()
            );
        }
    }

    private ReportTaskResponse toResponse(ReportTaskRecord record) {
        return new ReportTaskResponse(
                record.taskId(),
                record.chatId(),
                record.status(),
                record.title(),
                record.suggestions(),
                record.reportFileName(),
                record.reportDownloadPath(),
                record.error(),
                record.timestamp()
        );
    }

    private record ReportTaskRecord(
            String taskId,
            String chatId,
            String status,
            String title,
            List<String> suggestions,
            String reportFileName,
            String reportDownloadPath,
            String error,
            Instant timestamp
    ) {
    }
}
