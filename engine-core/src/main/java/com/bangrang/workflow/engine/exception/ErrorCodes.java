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

    // DAG 정의/검증 관련
    public static final String DAG_INVALID = "WF-E301";              // 한 가지 이상 위반의 종합
    public static final String DAG_NO_NODES = "WF-E302";             // 노드 0개
    public static final String DAG_NO_START_NODE = "WF-E303";        // incoming 0개 노드 부재
    public static final String DAG_NO_TERMINAL_NODE = "WF-E304";     // outgoing 0개 노드 부재
    public static final String DAG_CYCLE_DETECTED = "WF-E305";       // 사이클 존재
    public static final String DAG_SELF_LOOP = "WF-E306";            // 자기-참조 엣지
    public static final String DAG_UNKNOWN_ACTIVITY = "WF-E307";     // 미등록 액티비티 참조
    public static final String DAG_DANGLING_EDGE = "WF-E308";        // 엣지가 정의 외부 노드를 참조

    // 시스템/기타
    public static final String DLQ_NOTIFICATION_FAILED = "WF-E901";
    public static final String UNEXPECTED_ERROR = "WF-E999";

    private ErrorCodes() {}
}
