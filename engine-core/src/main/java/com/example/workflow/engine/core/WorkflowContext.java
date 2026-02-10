package com.example.workflow.engine.core;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 워크플로우 실행 중 한 인스턴스/액티비티의 실행 맥락을 표준화한 인터페이스.
 *
 * - 특정 DB 기술(JPA 등)에 종속되지 않도록 순수 POJO 기반 계약만 제공한다.
 * - 엔진 구현체는 본 인터페이스를 구현하여 컨텍스트를 주입/갱신한다.
 */
public interface WorkflowContext {

    /**
     * 워크플로우 인스턴스 식별자
     */
    String instanceId();

    /**
     * 워크플로우 이름 (@Workflow value 또는 클래스명)
     */
    String workflowName();

    /**
     * 현재 실행 중인 액티비티 이름 (@Activity value 또는 메서드명)
     */
    String currentActivityName();

    /**
     * 현재 액티비티의 시도 횟수 (첫 실행 = 1)
     */
    int attempt();

    /**
     * 현재 액티비티에 전달된 입력 객체 (JSON 직렬화 대상이 될 수 있는 POJO)
     */
    Object input();

    /**
     * 이전 액티비티의 출력(결과) 객체. 존재하지 않을 수 있다.
     */
    Optional<Object> previousOutput();

    /**
     * 컨텍스트 범위의 임시 속성 저장소. 엔진 구현은 필요 시 직렬화하여 보존할 수 있다.
     */
    Map<String, Object> attributes();

    /** 현재 시간(UTC 기준)을 제공. 엔진 테스트 용이성을 위해 주입 가능한 Clock 대체 수단 제공 목적. */
    default Instant now() { return Instant.now(); }

    /**
     * 엔진에 다음 액티비티 실행 의도를 전달한다. 구현체는 이 힌트를 받아 적절히 스케줄링한다.
     *
     * @param activityName 다음에 실행할 액티비티 이름
     * @param input 다음 액티비티에 전달할 입력
     */
    void setNext(String activityName, Object input);

    /** 다음 액티비티 이름 힌트 조회 (없을 수 있음). */
    Optional<String> nextActivityName();

    /** 다음 액티비티 입력 힌트 조회 (없을 수 있음). */
    Optional<Object> nextActivityInput();

    /**
     * 체크포인트 스냅샷 저장. 구현체는 객체를 JSON 등으로 직렬화하여 영속화할 수 있다.
     *
     * @param stateSnapshot 직렬화 가능한 임의의 스냅샷 객체
     */
    void saveState(Object stateSnapshot);

    /** 저장된 스냅샷 조회. 없을 수 있다. */
    Optional<Object> loadState();
}
