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
}

