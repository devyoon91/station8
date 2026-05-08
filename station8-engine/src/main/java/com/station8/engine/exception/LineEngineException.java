package com.station8.engine.exception;

/**
 * 라인 엔진에서 발생하는 예외의 기본 클래스.
 */
public class LineEngineException extends RuntimeException {
    private final String errorCode;

    public LineEngineException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LineEngineException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
