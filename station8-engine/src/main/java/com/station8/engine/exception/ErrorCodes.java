package com.station8.engine.exception;

/**
 * 워크플로우 엔진 에러 코드 정의.
 *
 * <p>코드 체계: ``WF-E<영역><번호>``</p>
 * <ul>
 *   <li>1xx — JSON / 직렬화</li>
 *   <li>2xx — Registry / Context / Activity / Line 실행 컨텍스트</li>
 *   <li>3xx — DAG 정의/검증</li>
 *   <li>4xx — Schedule / Cron</li>
 *   <li>5xx — Definition 저장소 / 라이프사이클</li>
 *   <li>6xx — Adapter (Spring Batch 등)</li>
 *   <li>9xx — 시스템 / 알림 / 미분류</li>
 * </ul>
 *
 * <p>사용자 액션 매핑은 ``docs/ERROR_CODES.md`` 참조.</p>
 *
 * <p>운영 가이드: 외부 API 응답에는 ``errorCode`` + ``message``를 함께 노출.
 * 내부 로깅은 추가로 stack trace를 포함하되 ``message``를 그대로 재사용.</p>
 */
public final class ErrorCodes {

    // 1xx — JSON / 직렬화
    public static final String JSON_SERIALIZATION_ERROR = "WF-E101";
    public static final String JSON_DESERIALIZATION_ERROR = "WF-E102";

    // 2xx — Registry / Context / Activity 실행
    public static final String WORKFLOW_NOT_FOUND = "WF-E201";
    public static final String ACTIVITY_NOT_FOUND = "WF-E202";
    public static final String INVALID_ARGUMENT = "WF-E203";
    public static final String CONTEXT_ATTRIBUTE_MISSING = "WF-E204";
    public static final String ACTIVITY_INVOCATION_FAILED = "WF-E205";
    public static final String INSTANCE_NOT_FOUND = "WF-E206";

    // 3xx — DAG 정의/검증
    public static final String DAG_INVALID = "WF-E301";              // 한 가지 이상 위반의 종합 컨테이너
    public static final String DAG_NO_NODES = "WF-E302";             // 노드 0개
    public static final String DAG_NO_START_NODE = "WF-E303";        // incoming 0개 노드 부재
    public static final String DAG_NO_TERMINAL_NODE = "WF-E304";     // outgoing 0개 노드 부재
    public static final String DAG_CYCLE_DETECTED = "WF-E305";       // 사이클
    public static final String DAG_SELF_LOOP = "WF-E306";            // 자기-참조 엣지
    public static final String DAG_UNKNOWN_ACTIVITY = "WF-E307";     // 미등록 액티비티 참조
    public static final String DAG_DANGLING_EDGE = "WF-E308";        // 엣지가 정의 외부 노드를 참조

    // 4xx — Schedule / Cron
    public static final String SCHEDULE_NOT_FOUND = "WF-E401";
    public static final String CRON_INVALID = "WF-E402";              // Spring CronExpression 파싱 실패
    public static final String SCHEDULE_TRIGGER_FAILED = "WF-E403";   // 폴러가 인스턴스 시작 실패

    // 5xx — Definition 라이프사이클
    public static final String DEFINITION_NOT_FOUND = "WF-E501";
    public static final String DEFINITION_NM_REQUIRED = "WF-E502";
    public static final String DEFINITION_NODES_REQUIRED = "WF-E503";

    // 6xx — Adapter (Spring Batch 등)
    public static final String BATCH_INPUT_BLANK = "WF-E601";
    public static final String BATCH_JOB_NAME_MISSING = "WF-E602";
    public static final String BATCH_JOB_NOT_REGISTERED = "WF-E603";
    public static final String BATCH_JOB_LAUNCH_FAILED = "WF-E604";
    public static final String BATCH_JOB_FAILED = "WF-E605";

    // 9xx — 시스템 / 알림 / 미분류
    public static final String DLQ_NOTIFICATION_FAILED = "WF-E901";
    public static final String UNEXPECTED_ERROR = "WF-E999";

    private ErrorCodes() {}
}
