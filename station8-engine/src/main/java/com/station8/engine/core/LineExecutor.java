package com.station8.engine.core;

/**
 * 라인 실행을 담당하는 상위 인터페이스.
 */
public interface LineExecutor {
    
    /**
     * 새로운 라인 인스턴스를 시작합니다.
     * 
     * @param workflowName 라인 이름 (@LineDefinition 어노테이션의 value 또는 클래스명)
     * @param input 입력 객체(JSON 직렬화 가능한 객체)
     * @return 생성된 인스턴스 ID
     */
    String startLine(String workflowName, Object input);
    
    /**
     * 중단된 라인을 특정 인스턴스 기준으로 재개합니다.
     *
     * @param instanceId 라인 인스턴스 ID
     */
    void resumeLine(String instanceId);

    /**
     * 인스턴스를 강제 종료합니다 (#101).
     *
     * <p>인스턴스를 {@code TERMINATED}로 마킹하고, 아직 시작하지 않은
     * {@code PENDING} / {@code WAITING_DEPENDENCIES} 액티비티들을 일괄
     * {@code TERMINATED}로 전이한다. 이미 {@code RUNNING} 상태인 액티비티는
     * 워커가 자연 완료할 때까지 그대로 둔다 (워커 인터럽트 위험).</p>
     *
     * <p>인스턴스 상태가 {@code RUNNING}이 아니면(이미 COMPLETED/FAILED/TERMINATED)
     * {@link IllegalStateException}을 던진다.</p>
     *
     * @param instanceId 라인 인스턴스 ID
     */
    void terminateLine(String instanceId);

    /**
     * #138 — {@link #terminateLine(String)}과 동일하지만 사유를 {@code OUTPUT_DATA}에 기록한다.
     *
     * <p>SLA 위반(auto-terminate)처럼 시스템이 자체 판단으로 종료할 때 사용. 사유는
     * {@code {"failureReason": "..."}} JSON으로 저장된다.</p>
     *
     * <p>인스턴스가 RUNNING이 아니면 idempotent하게 무시 (이미 다른 경로로 종료됐을 수 있음).</p>
     *
     * @param instanceId 라인 인스턴스 ID
     * @param reason     종료 사유 (예: "SLA violation: 3700s exceeds threshold 3600s")
     */
    void terminateLineWithReason(String instanceId, String reason);

    /**
     * 인스턴스를 {@code FAILED}로 마킹하고 사유를 {@code OUTPUT_DATA}에 기록한다 (#152).
     *
     * <p>{@link #terminateLine(String)}과 유사하지만 의미가 다르다 — TERMINATED는 운영자가
     * 명시적으로 멈춘 경우, FAILED는 엔진이 자체 판단으로 진행 불가 결정한 경우(엣지 조건
     * 0건 만족 / 조건 평가 예외 등). 활동들은 동일하게 PENDING/WAITING → TERMINATED.</p>
     *
     * <p>인스턴스 상태가 {@code RUNNING}이 아니면 idempotent하게 무시 (already failed/terminated).</p>
     *
     * @param instanceId 라인 인스턴스 ID
     * @param reason     실패 사유 — JSON으로 직렬화돼 {@code OUTPUT_DATA}에 저장
     */
    void failLine(String instanceId, String reason);
}

