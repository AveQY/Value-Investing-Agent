package com.aweqy.aiagent.exception;

public class UpstreamModelServiceException extends RuntimeException {

    public UpstreamModelServiceException(String message) {
        super(message);
    }

    public UpstreamModelServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
