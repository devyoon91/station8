package com.station8.engine.core;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 라인 실행 컨텍스트/액티비티 실행 맥락을 표준화한 인터페이스.
 *
 * - 특정 DB 기술(JPA 등)에 의존하지 않는 순수 POJO 기반 계약입니다.
 * - 엔진 구현체는 본 인터페이스를 통해 컨텍스트를 주입/교환합니다.
 */
public interface LineContext {

    /**
     * 라인 인스턴스 식별자
     */
    String instanceId();

    /**
     * 라인 이름 (@LineDefinition value 또는 클래스명)
     */
    String workflowName();

    /**
     * 현재 실행 중인 액티비티 이름 (@Activity value 또는 메서드명)
     */
    String currentActivityName();

    /**
     * 현재 활동의 DAG 노드 ID. legacy/linear 모드 (DAG 외부)에서는 null.
     *
     * <p>#278 — TaskExecutor의 retry 경로가 새 활동 row를 만들 때 nodeId를 보존하기 위해
     * 컨텍스트에 노출. 이전엔 retry row가 NODE_ID=NULL로 만들어져 DAG fan-out이 차단되고
     * row가 누적되는 데이터 손상이 발생했음.</p>
     */
    default String nodeId() { return null; }

    /**
     * 현재 액티비티의 시도 횟수 (최초 실행 = 1)
     */
    int attempt();

    /**
     * 현재 액티비티에 전달된 입력 객체 (JSON 직렬화 가능한 POJO)
     */
    Object input();

    /**
     * 이전 액티비티의 출력(결과) 객체. 없을 수 있음.
     */
    Optional<Object> previousOutput();

    /**
     * 컨텍스트 부가/임시 속성. 엔진 구현이 필요 시 직렬화하여 보존할 수 있음.
     */
    Map<String, Object> attributes();

    /**
     * 인스턴스 단위 runtime parameters (#134). 즉시 실행 시점에 사용자가 모달에서 입력한
     * named override 맵. 활동 코드는 이 맵으로 옵션 값을 받아온다.
     *
     * <pre>{@code
     * @Activity("REPORT")
     * public String report(String input, LineContext ctx) {
     *     String date = ctx.runtimeParams().getOrDefault("date", "today");
     *     ...
     * }
     * }</pre>
     *
     * <p>옵션 미설정 또는 빈 맵이면 {@link Map#of()} 반환.</p>
     */
    default Map<String, String> runtimeParams() { return Map.of(); }

    /** 현재 시간(UTC 기준)을 반환. 엔진 테스트 용이성을 위해 Clock 추상화 대체 가능. */
    default Instant now() { return Instant.now(); }

    /**
     * 엔진에 다음 액티비티 실행 의도를 전달한다. 구현체는 이 힌트를 받아 전이를 스케줄링한다.
     *
     * @param activityName 다음에 실행할 액티비티 이름
     * @param input 다음 액티비티 입력
     */
    void setNext(String activityName, Object input);

    /** 다음 액티비티 이름 힌트 조회 (없을 수 있음). */
    Optional<String> nextActivityName();

    /** 다음 액티비티 입력 힌트 조회 (없을 수 있음). */
    Optional<Object> nextActivityInput();

    /**
     * 체크포인트 스냅샷을 JSON 등으로 직렬화하여 저장한다.
     *
     * @param stateSnapshot 직렬화 가능한 임의의 스냅샷 객체
     */
    void saveState(Object stateSnapshot);

    /** 저장된 스냅샷 조회. 없을 수 있음. */
    Optional<Object> loadState();
}

