package com.bangrang.workflow.engine.exception;

/**
 * 워크플로우 엔진 에러 코드 정의.
 */
public final class ErrorCodes {
    
    // JSON 관련
    public static final String JSON_SERIALIZATION_ERROR = "WF-E101";
    public static final String JSON_DESERIALIZATION_ERROR = "WF-E102";
    
    // 워크플로우 실행 관련
    public static final String WORKFLOW_NOT_FOUND = "WF-E201";
    public static final String ACTIVITY_NOT_FOUND = "WF-E202";
    public static final String INVALID_ARGUMENT = "WF-E203";
    public static final String CONTEXT_ATTRIBUTE_MISSING = "WF-E204";
    
    // 시스템/기타
    public static final String DLQ_NOTIFICATION_FAILED = "WF-E901";
    public static final String UNEXPECTED_ERROR = "WF-E999";

    private ErrorCodes() {}
}
