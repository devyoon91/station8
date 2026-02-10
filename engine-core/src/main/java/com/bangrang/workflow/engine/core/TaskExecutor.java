package com.bangrang.workflow.engine.core;

import java.time.Duration;

/**
 * 액티비티 실행 및 다음 단계 전이를 담당하는 추상 실행기 인터페이스.
 *
 * - 특정 영속 기술(JPA, MyBatis 등)이나 스케줄러 구현에 의존하지 않는다.
 * - 엔진 코어는 본 인터페이스의 구현체를 통해 실제 실행/전이/체크포인트를 처리한다.
 */
public interface TaskExecutor {

    /**
     * 컨텍스트의 현재 액티비티를 실행한다. 구현체는 @Activity 메타데이터나 재시도 룰을 해석할 수 있다.
     */
    void executeCurrent(WorkflowContext context);

    /**
     * 다음 액티비티를 스케줄링한다. 보통 현재 트랜잭션 커밋 이후 비동기로 실행되도록 위임된다.
     *
     * @param context 현재 실행 컨텍스트
     * @param nextActivityName 다음 액티비티 이름
     * @param input 다음 액티비티 입력
     */
    void scheduleNext(WorkflowContext context, String nextActivityName, Object input);

    /**
     * 현재 액티비티를 성공 처리하고 결과를 컨텍스트/히스토리에 반영한다.
     *
     * @param context 실행 컨텍스트
     * @param output 출력 결과(직렬화 가능)
     */
    void complete(WorkflowContext context, Object output);

    /**
     * 현재 액티비티를 실패 처리하고, 재시도가 필요한 경우 backoff 지연을 반영한다.
     *
     * @param context 실행 컨텍스트
     * @param error 실패 원인
     * @param nextBackoff 재시도 대기 시간(없으면 즉시 또는 정책 기본값 적용)
     */
    void fail(WorkflowContext context, Throwable error, Duration nextBackoff);

    /**
     * 체크포인트 스냅샷을 영속 저장한다. 구현체는 JSON 직렬화 등 구체 방식은 자유롭게 선택한다.
     */
    void checkpoint(WorkflowContext context, Object stateSnapshot);
}

