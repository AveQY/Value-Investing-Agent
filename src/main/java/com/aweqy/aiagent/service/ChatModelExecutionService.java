package com.aweqy.aiagent.service;

import com.aweqy.aiagent.exception.UpstreamModelServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
@Slf4j
public class ChatModelExecutionService {

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MILLIS = 1200L;
    private static final int FALLBACK_STREAM_CHUNK_SIZE = 48;
    private static final String MODEL_UNAVAILABLE_MESSAGE = "所选模型服务暂时超时或不可用，系统已自动重试，但仍未成功。请稍后重试，或切换到其他模型后再试。";

    public <T> T executeWithRetry(String operationName, Supplier<T> action) {
        return executeWithRetry(operationName, action, true);
    }

    public <T> T executeWithRetry(String operationName, Supplier<T> action, boolean logTerminalFailureAsError) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (!isRetryable(ex) || attempt >= MAX_ATTEMPTS) {
                    break;
                }

                log.warn("Transient upstream model error during {}. attempt={}/{}. Retrying after {} ms.",
                        operationName, attempt, MAX_ATTEMPTS, RETRY_DELAY_MILLIS, ex);
                sleepQuietly(RETRY_DELAY_MILLIS);
            }
        }

        throw adaptFailure(operationName, lastFailure, logTerminalFailureAsError);
    }

    public Flux<String> streamWithRetryAndFallback(String operationName,
                                                   Supplier<Flux<String>> streamSupplier,
                                                   Supplier<String> fallbackAction) {
        return Flux.defer(() -> {
            AtomicBoolean hasEmittedChunks = new AtomicBoolean(false);

            return Flux.defer(streamSupplier)
                    .doOnNext(chunk -> hasEmittedChunks.set(true))
                    .retryWhen(Retry.fixedDelay(Math.max(0, MAX_ATTEMPTS - 1), Duration.ofMillis(RETRY_DELAY_MILLIS))
                            .filter(this::isRetryable)
                            .doBeforeRetry(signal -> log.warn(
                                    "Transient upstream model stream error during {}. attempt={}/{}. Retrying stream.",
                                    operationName,
                                    signal.totalRetries() + 1,
                                    MAX_ATTEMPTS,
                                    signal.failure()))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .onErrorResume(ex -> {
                        if (!isRetryable(ex)) {
                            return Flux.error(adaptFailure(operationName, ex, true));
                        }
                        if (hasEmittedChunks.get()) {
                            return Flux.error(adaptFailure(operationName, ex, true));
                        }

                        log.warn("Streaming failed before the first chunk during {}. Falling back to non-stream call.", operationName, ex);
                        String fallbackContent = executeWithRetry(operationName + " fallback", fallbackAction);
                        return Flux.fromIterable(splitIntoChunks(fallbackContent));
                    });
        });
    }

    private RuntimeException adaptFailure(String operationName, Throwable failure, boolean logTerminalFailureAsError) {
        if (failure instanceof UpstreamModelServiceException upstreamModelServiceException) {
            return upstreamModelServiceException;
        }
        if (isRetryable(failure)) {
            if (logTerminalFailureAsError) {
                log.error("Upstream model invocation failed after retries during {}.", operationName, failure);
            } else {
                log.warn("Upstream model invocation failed after retries during {}. Caller will decide fallback handling.", operationName);
                log.debug("Handled upstream model failure during {}.", operationName, failure);
            }
            return new UpstreamModelServiceException(MODEL_UNAVAILABLE_MESSAGE, failure);
        }
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(failure);
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TransientAiException transientAiException) {
                String message = transientAiException.getMessage();
                if (message != null && message.matches(".*\\b(408|429|500|502|503|504)\\b.*")) {
                    return true;
                }
            }
            if (current instanceof WebClientResponseException responseException) {
                int statusCode = responseException.getStatusCode().value();
                if (statusCode == 408 || statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
                    return true;
                }
            }
            if (current instanceof WebClientRequestException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("gateway timeout")
                        || normalized.contains("timed out")
                        || normalized.contains("timeout")
                        || normalized.contains("connection reset")
                        || normalized.contains("connection prematurely closed")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    private List<String> splitIntoChunks(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < content.length(); start += FALLBACK_STREAM_CHUNK_SIZE) {
            int end = Math.min(content.length(), start + FALLBACK_STREAM_CHUNK_SIZE);
            chunks.add(content.substring(start, end));
        }
        return chunks;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
