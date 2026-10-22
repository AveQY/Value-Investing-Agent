package com.aweqy.aiagent.controller;

import com.aweqy.aiagent.app.FundApp;
import com.aweqy.aiagent.config.ToolLoopRuntimeConfig;
import com.aweqy.aiagent.model.AdvisorStyle;
import com.aweqy.aiagent.model.AcquireChatSessionLockRequest;
import com.aweqy.aiagent.model.ChatHistoryResponse;
import com.aweqy.aiagent.model.ChatModelCatalogResponse;
import com.aweqy.aiagent.model.ChatModelSummary;
import com.aweqy.aiagent.model.ChatRuntimeConfigResponse;
import com.aweqy.aiagent.model.ChatRequest;
import com.aweqy.aiagent.model.ChatResponse;
import com.aweqy.aiagent.model.ChatSessionLockResponse;
import com.aweqy.aiagent.model.ChatSessionCatalogResponse;
import com.aweqy.aiagent.model.CreateChatModelRequest;
import com.aweqy.aiagent.model.DeleteHistoryResponse;
import com.aweqy.aiagent.model.DiscoverChatModelsRequest;
import com.aweqy.aiagent.model.DiscoverChatModelsResponse;
import com.aweqy.aiagent.model.ErrorResponse;
import com.aweqy.aiagent.model.ReportResponse;
import com.aweqy.aiagent.model.ReportTaskResponse;
import com.aweqy.aiagent.model.ReportTaskSubmitResponse;
import com.aweqy.aiagent.service.ChatHistoryService;
import com.aweqy.aiagent.service.ChatModelRegistryService;
import com.aweqy.aiagent.service.ReportDocumentService;
import com.aweqy.aiagent.service.ReportTaskService;
import com.aweqy.aiagent.service.SessionLockService;
import com.aweqy.aiagent.service.ValueAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@Validated
@RequestMapping("/fund")
@Tag(name = "FundApp API", description = "智能投资顾问 RESTful API")
public class FundAppController {

    private final FundApp fundApp;
    private final ChatHistoryService chatHistoryService;
    private final ChatModelRegistryService chatModelRegistryService;
    private final ValueAnalysisService valueAnalysisService;
    private final ReportDocumentService reportDocumentService;
    private final ReportTaskService reportTaskService;
    private final SessionLockService sessionLockService;

    public FundAppController(FundApp fundApp,
                             ChatHistoryService chatHistoryService,
                             ChatModelRegistryService chatModelRegistryService,
                             ValueAnalysisService valueAnalysisService,
                             ReportDocumentService reportDocumentService,
                             ReportTaskService reportTaskService,
                             SessionLockService sessionLockService) {
        this.fundApp = fundApp;
        this.chatHistoryService = chatHistoryService;
        this.chatModelRegistryService = chatModelRegistryService;
        this.valueAnalysisService = valueAnalysisService;
        this.reportDocumentService = reportDocumentService;
        this.reportTaskService = reportTaskService;
        this.sessionLockService = sessionLockService;
    }

    @PostMapping("/chat")
    @Operation(summary = "普通聊天",
            responses = {
                    @ApiResponse(responseCode = "200", description = "调用成功"),
                    @ApiResponse(responseCode = "400", description = "请求参数错误",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            })
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                             HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        ChatModelSummary model = chatModelRegistryService.getModelSummary(request.modelId());
        String content = fundApp.doChat(request.message(), request.chatId(), request.advisorStyle(), model.id());
        return ResponseEntity.ok(buildChatResponse(
                request.chatId(),
                "CHAT",
                model.id(),
                model.name(),
                sanitizeGeneralModeAdvisorStyle(request.advisorStyle()),
                content
        ));
    }

    @PostMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "普通聊天（SSE）")
    public SseEmitter chatSse(@Valid @RequestBody ChatRequest request,
                              HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        String modelId = chatModelRegistryService.resolveModelId(request.modelId());
        return createSseEmitter(fundApp.doChatStream(request.message(), request.chatId(), request.advisorStyle(), modelId));
    }

    @PostMapping("/chat/report")
    @Operation(summary = "生成结构化投资报告（兼容同步接口）")
    public ResponseEntity<ReportResponse> chatWithReport(@Valid @RequestBody ChatRequest request,
                                                         HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
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
        ReportResponse response = new ReportResponse(
                request.chatId(),
                report.title(),
                report.suggestions(),
                report.reportFileName(),
                report.reportDownloadPath(),
                Instant.now()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/chat/report/tasks")
    @Operation(summary = "提交结构化投资报告任务")
    public ResponseEntity<ReportTaskSubmitResponse> createReportTask(@Valid @RequestBody ChatRequest request,
                                                                     HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        ReportTaskResponse task = reportTaskService.createTask(request);
        return ResponseEntity.ok(new ReportTaskSubmitResponse(task.taskId(), task.chatId(), task.status(), task.timestamp()));
    }

    @GetMapping("/chat/report/tasks/{taskId}")
    @Operation(summary = "查询结构化投资报告任务状态")
    public ResponseEntity<ReportTaskResponse> getReportTask(
            @PathVariable
            @Size(max = 120, message = "taskId 长度不能超过 120")
            String taskId,
            HttpServletRequest httpServletRequest) {
        String chatId = httpServletRequest.getParameter("chatId");
        if (StringUtils.hasText(chatId)) {
            assertSessionAccess(chatId, httpServletRequest);
        }
        return ResponseEntity.ok(reportTaskService.getTask(taskId));
    }

    @GetMapping(value = "/reports/{fileName:.+}/download", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "下载结构化报告 PDF")
    public ResponseEntity<Resource> downloadReport(
            @PathVariable
            @Size(max = 120, message = "fileName 长度不能超过 120")
            @Pattern(regexp = "^[A-Za-z0-9._-]+\\.pdf$", message = "fileName 仅支持安全的 PDF 文件名")
            String fileName) {
        java.nio.file.Path reportFile = reportDocumentService.resolveExistingPdf(fileName);
        if (reportFile == null) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(reportFile);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }

    @PostMapping("/chat/rag")
    @Operation(summary = "RAG 增强对话")
    public ResponseEntity<ChatResponse> chatWithRag(@Valid @RequestBody ChatRequest request,
                                                    HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        ChatModelSummary model = chatModelRegistryService.getModelSummary(request.modelId());
        String content = fundApp.doChatWithRag(request.message(), request.chatId(), request.advisorStyle(), model.id());
        return ResponseEntity.ok(buildChatResponse(
                request.chatId(),
                "RAG",
                model.id(),
                model.name(),
                sanitizeGeneralModeAdvisorStyle(request.advisorStyle()),
                content
        ));
    }

    @PostMapping(value = "/chat/rag/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "RAG 增强对话（SSE）")
    public SseEmitter chatWithRagSse(@Valid @RequestBody ChatRequest request,
                                     HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        String modelId = chatModelRegistryService.resolveModelId(request.modelId());
        return createSseEmitter(fundApp.doChatWithRagStream(request.message(), request.chatId(), request.advisorStyle(), modelId));
    }

    @PostMapping("/chat/tools")
    @Operation(summary = "工具增强对话")
    public ResponseEntity<ChatResponse> chatWithTools(@Valid @RequestBody ChatRequest request,
                                                      HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        ChatModelSummary model = chatModelRegistryService.getModelSummary(request.modelId());
        String content = fundApp.doChatWithTools(request.message(), request.chatId(), request.advisorStyle(), model.id());
        return ResponseEntity.ok(buildChatResponse(
                request.chatId(),
                "TOOLS",
                model.id(),
                model.name(),
                sanitizeToolsModeAdvisorStyle(request.advisorStyle()),
                content
        ));
    }

    @PostMapping(value = "/chat/tools/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工具增强对话（SSE）")
    public SseEmitter chatWithToolsSse(@Valid @RequestBody ChatRequest request,
                                       HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        String modelId = chatModelRegistryService.resolveModelId(request.modelId());
        return createSseEmitter(fundApp.doChatWithToolsStream(request.message(), request.chatId(), request.advisorStyle(), modelId));
    }

    @PostMapping("/chat/value")
    @Operation(summary = "价值分析模式")
    public ResponseEntity<ChatResponse> chatWithValueAnalysis(@Valid @RequestBody ChatRequest request,
                                                              HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        ChatModelSummary model = chatModelRegistryService.getModelSummary(request.modelId());
        String content = valueAnalysisService.analyze(request.message(), request.chatId(), request.advisorStyle(), model.id());
        return ResponseEntity.ok(buildChatResponse(
                request.chatId(),
                "VALUE",
                model.id(),
                model.name(),
                request.advisorStyle(),
                content
        ));
    }

    @PostMapping(value = "/chat/value/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "价值分析模式（SSE）")
    public SseEmitter chatWithValueAnalysisSse(@Valid @RequestBody ChatRequest request,
                                               HttpServletRequest httpServletRequest) {
        assertSessionAccess(request.chatId(), httpServletRequest);
        String modelId = chatModelRegistryService.resolveModelId(request.modelId());
        return createSseEmitter(valueAnalysisService.analyzeStream(request.message(), request.chatId(), request.advisorStyle(), modelId));
    }

    @PostMapping("/session-lock/{chatId}/acquire")
    @Operation(summary = "获取会话占用锁")
    public ResponseEntity<ChatSessionLockResponse> acquireSessionLock(
            @PathVariable
            @Size(max = 64, message = "chatId 长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "chatId 只能包含字母、数字、下划线和短横线")
            String chatId,
            @Valid @RequestBody AcquireChatSessionLockRequest lockRequest) {
        return ResponseEntity.ok(sessionLockService.acquire(chatId, lockRequest.clientId()));
    }

    @PostMapping("/session-lock/{chatId}/heartbeat")
    @Operation(summary = "续租会话占用锁")
    public ResponseEntity<ChatSessionLockResponse> heartbeatSessionLock(
            @PathVariable
            @Size(max = 64, message = "chatId 长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "chatId 只能包含字母、数字、下划线和短横线")
            String chatId,
            HttpServletRequest httpServletRequest) {
        return ResponseEntity.ok(sessionLockService.heartbeat(
                chatId,
                httpServletRequest.getHeader(SessionLockService.CLIENT_ID_HEADER),
                httpServletRequest.getHeader(SessionLockService.LEASE_TOKEN_HEADER)
        ));
    }

    @PostMapping("/session-lock/{chatId}/release")
    @Operation(summary = "释放会话占用锁")
    public ResponseEntity<Void> releaseSessionLock(
            @PathVariable
            @Size(max = 64, message = "chatId 长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "chatId 只能包含字母、数字、下划线和短横线")
            String chatId,
            HttpServletRequest httpServletRequest) {
        sessionLockService.release(
                chatId,
                httpServletRequest.getHeader(SessionLockService.CLIENT_ID_HEADER),
                httpServletRequest.getHeader(SessionLockService.LEASE_TOKEN_HEADER)
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/models")
    @Operation(summary = "获取可用聊天模型列表")
    public ResponseEntity<ChatModelCatalogResponse> getModels() {
        return ResponseEntity.ok(chatModelRegistryService.listModels());
    }

    @PostMapping("/models/discover")
    @Operation(summary = "获取远端聊天模型列表")
    public ResponseEntity<DiscoverChatModelsResponse> discoverModels(
            @Valid @RequestBody DiscoverChatModelsRequest request) {
        return ResponseEntity.ok(new DiscoverChatModelsResponse(
                chatModelRegistryService.discoverModels(request.baseUrl(), request.apiKey()),
                Instant.now()
        ));
    }

    @GetMapping("/runtime-config")
    @Operation(summary = "获取聊天运行时配置")
    public ResponseEntity<ChatRuntimeConfigResponse> getRuntimeConfig() {
        return ResponseEntity.ok(ToolLoopRuntimeConfig.toResponse());
    }

    @PostMapping("/models")
    @Operation(summary = "新增聊天模型")
    public ResponseEntity<ChatModelSummary> createModel(@Valid @RequestBody CreateChatModelRequest request) {
        return ResponseEntity.ok(chatModelRegistryService.addCustomModel(request));
    }

    @GetMapping("/history/{chatId}")
    @Operation(summary = "获取聊天历史")
    public ResponseEntity<ChatHistoryResponse> getHistory(
            @PathVariable
            @Size(max = 64, message = "chatId 长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "chatId 只能包含字母、数字、下划线和短横线")
            String chatId,
            HttpServletRequest httpServletRequest) {
        assertSessionAccess(chatId, httpServletRequest);
        return ResponseEntity.ok(chatHistoryService.getHistory(chatId));
    }

    @GetMapping("/history/sessions")
    @Operation(summary = "获取会话列表")
    public ResponseEntity<ChatSessionCatalogResponse> listHistorySessions() {
        return ResponseEntity.ok(chatHistoryService.listSessions());
    }

    @DeleteMapping("/history/{chatId}")
    @Operation(summary = "删除聊天历史")
    public ResponseEntity<DeleteHistoryResponse> deleteHistory(
            @PathVariable
            @Size(max = 64, message = "chatId 长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "chatId 只能包含字母、数字、下划线和短横线")
            String chatId,
            HttpServletRequest httpServletRequest) {
        assertSessionAccess(chatId, httpServletRequest);
        chatHistoryService.deleteHistory(chatId);
        return ResponseEntity.ok(new DeleteHistoryResponse(chatId, "聊天历史已删除", Instant.now()));
    }

    private ChatResponse buildChatResponse(String chatId,
                                           String mode,
                                           String modelId,
                                           String modelName,
                                           String advisorStyle,
                                           String content) {
        return new ChatResponse(chatId, mode, modelId, modelName, AdvisorStyle.resolve(advisorStyle).code(), content, Instant.now());
    }

    private String sanitizeGeneralModeAdvisorStyle(String advisorStyle) {
        AdvisorStyle style = AdvisorStyle.resolve(advisorStyle);
        return style.isBuffettStyle() ? AdvisorStyle.DEFAULT.code() : style.code();
    }

    private String sanitizeToolsModeAdvisorStyle(String advisorStyle) {
        return AdvisorStyle.resolve(advisorStyle).code();
    }

    private void assertSessionAccess(String chatId, HttpServletRequest httpServletRequest) {
        sessionLockService.assertAccess(
                chatId,
                httpServletRequest.getHeader(SessionLockService.CLIENT_ID_HEADER),
                httpServletRequest.getHeader(SessionLockService.LEASE_TOKEN_HEADER)
        );
    }

    private SseEmitter createSseEmitter(Flux<String> stream) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        AtomicReference<Disposable> heartbeatRef = new AtomicReference<>();
        AtomicBoolean connectionClosed = new AtomicBoolean(false);

        sendConnectedEvent(emitter, connectionClosed);

        Disposable subscription = stream.subscribe(
                chunk -> sendChunk(emitter, chunk, subscriptionRef, heartbeatRef, connectionClosed),
                error -> handleStreamError(emitter, error, subscriptionRef, heartbeatRef, connectionClosed),
                () -> completeEmitter(emitter, connectionClosed, heartbeatRef)
        );

        subscriptionRef.set(subscription);
        heartbeatRef.set(Flux.interval(java.time.Duration.ofSeconds(10))
                .subscribe(ignored -> sendPing(emitter, subscriptionRef, heartbeatRef, connectionClosed)));

        emitter.onCompletion(() -> {
            connectionClosed.set(true);
            disposeSubscription(heartbeatRef.get());
        });
        emitter.onTimeout(() -> {
            connectionClosed.set(true);
            completeEmitter(emitter, connectionClosed, heartbeatRef);
        });
        emitter.onError(error -> {
            connectionClosed.set(true);
            disposeSubscription(heartbeatRef.get());
            if (!isClientDisconnectException(error)) {
                disposeSubscription(subscriptionRef.get());
            }
        });

        return emitter;
    }

    private void sendConnectedEvent(SseEmitter emitter, AtomicBoolean connectionClosed) {
        if (connectionClosed.get()) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("connected", true)));
        } catch (Exception ex) {
            if (isClientDisconnectException(ex)) {
                connectionClosed.set(true);
                completeEmitter(emitter, connectionClosed);
                return;
            }
            throw new IllegalStateException("failed to send SSE connected event", ex);
        }
    }

    private void sendPing(SseEmitter emitter,
                          AtomicReference<Disposable> subscriptionRef,
                          AtomicReference<Disposable> heartbeatRef,
                          AtomicBoolean connectionClosed) {
        if (connectionClosed.get()) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("ping")
                    .data(Map.of("timestamp", Instant.now().toString())));
        } catch (Exception ex) {
            connectionClosed.set(true);
            disposeSubscription(subscriptionRef.get());
            disposeSubscription(heartbeatRef.get());
            completeEmitter(emitter, connectionClosed, heartbeatRef);
        }
    }

    private void sendChunk(SseEmitter emitter,
                           String chunk,
                           AtomicReference<Disposable> subscriptionRef,
                           AtomicReference<Disposable> heartbeatRef,
                           AtomicBoolean connectionClosed) {
        if (connectionClosed.get()) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(chunk));
        } catch (Exception ex) {
            if (isClientDisconnectException(ex)) {
                connectionClosed.set(true);
                disposeSubscription(heartbeatRef.get());
                completeEmitter(emitter, connectionClosed, heartbeatRef);
                return;
            }
            connectionClosed.set(true);
            disposeSubscription(subscriptionRef.get());
            disposeSubscription(heartbeatRef.get());
            handleStreamError(emitter, ex, subscriptionRef, heartbeatRef, connectionClosed);
        }
    }

    private void handleStreamError(SseEmitter emitter,
                                   Throwable error,
                                   AtomicReference<Disposable> subscriptionRef,
                                   AtomicReference<Disposable> heartbeatRef,
                                   AtomicBoolean connectionClosed) {
        connectionClosed.set(true);

        if (isClientDisconnectException(error)) {
            disposeSubscription(heartbeatRef.get());
            completeEmitter(emitter, connectionClosed, heartbeatRef);
            return;
        }

        disposeSubscription(subscriptionRef.get());
        disposeSubscription(heartbeatRef.get());

        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", error.getMessage() == null ? "流式响应失败" : error.getMessage())));
        } catch (Exception ignored) {
            // Ignore secondary transport exceptions and close the emitter quietly.
        } finally {
            completeEmitter(emitter, connectionClosed, heartbeatRef);
        }
    }

    private void disposeSubscription(Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private void completeEmitter(SseEmitter emitter, AtomicBoolean connectionClosed) {
        completeEmitter(emitter, connectionClosed, null);
    }

    private void completeEmitter(SseEmitter emitter,
                                 AtomicBoolean connectionClosed,
                                 AtomicReference<Disposable> heartbeatRef) {
        if (connectionClosed != null) {
            connectionClosed.set(true);
        }
        if (heartbeatRef != null) {
            disposeSubscription(heartbeatRef.get());
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Ignore transport shutdown races triggered by page refresh or network disconnect.
        }
    }

    private boolean isClientDisconnectException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            if ("org.springframework.web.context.request.async.AsyncRequestNotUsableException".equals(className)
                    || "org.apache.catalina.connector.ClientAbortException".equals(className)) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("connection aborted")
                        || normalized.contains("an established connection was aborted")
                        || normalized.contains("你的主机中的软件中止了一个已建立的连接")
                        || normalized.contains("远程主机强迫关闭了一个现有的连接")) {
                    return true;
                }
            }

            current = current.getCause();
        }
        return false;
    }
}
