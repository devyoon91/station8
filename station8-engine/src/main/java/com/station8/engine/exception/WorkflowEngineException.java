package com.station8.engine.exception;

/**
 * 워크플로우 엔진에서 발생하는 예외의 기본 클래스.
 */
public class WorkflowEngineException extends RuntimeException {
    private final String errorCode;

    public WorkflowEngineException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkflowEngineException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
