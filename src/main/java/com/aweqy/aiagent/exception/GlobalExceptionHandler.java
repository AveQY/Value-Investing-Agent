package com.aweqy.aiagent.exception;

import com.aweqy.aiagent.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                      HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "请求参数校验失败", request.getRequestURI(), fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(violation -> fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage()));
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "请求参数校验失败", request.getRequestURI(), fieldErrors);
    }

    @ExceptionHandler(ChatHistoryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleChatHistoryNotFound(ChatHistoryNotFoundException ex,
                                                                   HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(ChatSessionAccessException.class)
    public ResponseEntity<ErrorResponse> handleChatSessionAccess(ChatSessionAccessException ex,
                                                                 HttpServletRequest request) {
        return buildErrorResponse(ex.getStatus(), ex.getMessage(), request.getRequestURI(), ex.getDetails());
    }

    @ExceptionHandler(UpstreamModelServiceException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamModelService(UpstreamModelServiceException ex,
                                                                    HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex,
                                                            HttpServletRequest request) {
        return buildDisconnectedResponse(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex,
                                                                        HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception ex, HttpServletRequest request) {
        if (isClientDisconnectException(ex)) {
            return buildDisconnectedResponse(request);
        }
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request.getRequestURI(), null);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status,
                                                             String message,
                                                             String path,
                                                             Map<String, String> details) {
        ErrorResponse errorResponse = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                details
        );
        return ResponseEntity.status(status).body(errorResponse);
    }

    private ResponseEntity<Void> buildDisconnectedResponse(HttpServletRequest request) {
        return ResponseEntity.noContent().build();
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
