package com.station8.engine.core;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 라인 인스턴스의 실행 컨텍스트 — 활동(Activity) 메서드가 호스트의 상태와 다음 단계 결정을 주고받는 단일 접점 (#321).
 *
 * <h3>안정성 약속</h3>
 *
 * <p>본 인터페이스는 station8-engine-api 모듈의 일부로, semver 적용 대상이다. 0.1.0부터:</p>
 * <ul>
 *   <li>MAJOR — abstract 메서드 추가/제거/시그니처 변경</li>
 *   <li>MINOR — default 메서드 추가 (본 인터페이스에 새 default 메서드 등장)</li>
 *   <li>PATCH — javadoc / 비기능 변경</li>
 * </ul>
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>호스트 런타임: {@code com.station8.engine.core.DefaultLineContext} (station8-engine 내부)</li>
 *   <li>플러그인 테스트: {@code com.station8.engine.test.InMemoryLineContext} ({@code station8-engine-test} 모듈, #319)</li>
 *   <li>Mockito mock — 가벼운 케이스용</li>
 * </ul>
 *
 * <h3>호출 모델</h3>
 *
 * <p>한 인스턴스의 한 활동 호출마다 새 컨텍스트가 호스트 측에서 생성되며, 활동 메서드 내에서만 단일 스레드로
 * 사용된다고 가정한다 — 본 인터페이스의 mutator({@link #setNext}, {@link #saveState})는 스레드 안전성을
 * 보장하지 않는다. 활동이 직접 thread를 띄워 컨텍스트를 공유하면 미정의 동작.</p>
 */
public interface LineContext {

    /**
     * 라인 인스턴스 식별자 (UUID 등). 같은 라인 정의의 여러 동시 인스턴스를 구분.
     *
     * @return 인스턴스 ID, null 아님
     */
    String instanceId();

    /**
     * 라인(워크플로우) 이름. {@code @LineDefinition value} 또는 클래스명.
     *
     * @return 라인 이름, null 아님
     */
    String workflowName();

    /**
     * 현재 실행 중인 활동 이름. {@code @Activity value} 또는 메서드명.
     *
     * @return 활동 이름, null 아님
     */
    String currentActivityName();

    /**
     * 현재 활동의 DAG 노드 ID. legacy/linear 모드(DAG 외부)에서는 null.
     *
     * <p>#278 — TaskExecutor의 retry 경로가 새 활동 row를 만들 때 nodeId를 보존하기 위해
     * 컨텍스트에 노출. 이전엔 retry row가 NODE_ID=NULL로 만들어져 DAG fan-out이 차단되고
     * row가 누적되는 데이터 손상이 발생했음.</p>
     *
     * @return 노드 ID, linear 모드에서는 null
     */
    default String nodeId() { return null; }

    /**
     * 현재 활동의 시도 횟수. 최초 실행 = 1, 첫 재시도 = 2, ...
     *
     * @return 시도 횟수 (≥ 1)
     */
    int attempt();

    /**
     * 현재 활동이 재시도 호출인지 여부 — {@code attempt() > 1} 의 별칭 (#321).
     *
     * <p>retry 카운트 매직 넘버 없이 의도를 드러내는 헬퍼. 활동 코드의 가독성과 일관성용:</p>
     *
     * <pre>{@code
     * @Activity("SEND_EMAIL")
     * public String send(String input, LineContext ctx) {
     *     if (ctx.isRetry()) {
     *         // idempotency 가드 — 이미 보냈는지 외부 시스템에 확인
     *     }
     *     ...
     * }
     * }</pre>
     *
     * @return attempt() &gt; 1 이면 true
     */
    default boolean isRetry() { return attempt() > 1; }

    /**
     * 현재 활동에 전달된 입력 객체. JSON 직렬화 가능한 POJO여야 한다 — Map / List / String / Number /
     * Boolean / null 의 조합.
     *
     * <p>실제 타입은 활동 등록 방식에 따라 다르다 — String 활동 시그니처는 raw JSON 문자열,
     * record/POJO 시그니처는 Jackson 으로 역직렬화된 객체. 활동 메서드 파라미터로 받는 게 정공이고
     * 본 메서드는 컨텍스트 차원에서 입력을 다시 보고 싶을 때만 사용.</p>
     *
     * @return 입력 객체, 빈 입력은 null 또는 빈 Map
     */
    Object input();

    /**
     * 직전 활동의 출력. 최초 활동 또는 선행 활동이 출력 안 했으면 empty.
     *
     * <p>표현식 엔진의 {@code $prev} binding이 이 값으로 해소된다. 평가 시점은 활동 입장 전이므로
     * 본 메서드가 보는 값은 직전 활동이 반환한 그대로 (host가 가공하지 않음).</p>
     *
     * @return 이전 출력 (구현체별로 raw POJO 또는 JSON 문자열)
     */
    Optional<Object> previousOutput();

    /**
     * 컨텍스트 부가/임시 속성 맵 — 활동 간 데이터 공유의 가장 가벼운 길.
     *
     * <p>구현체는 unmodifiable view 또는 mutable 맵을 반환할 수 있다 — 활동 코드는 본 맵을 직접
     * put/remove하지 않는 게 안전하다 (구현체에 따라 UnsupportedOperationException). 영속화가
     * 필요한 상태는 {@link #saveState}로 별도 보관하는 게 정공.</p>
     *
     * @return 속성 맵 — 키는 활동/시점이 합의한 문자열
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
     *
     * @return runtime params (불변 또는 mutable, 구현체별)
     */
    default Map<String, String> runtimeParams() { return Map.of(); }

    /**
     * 현재 시간(UTC 기준). 테스트의 결정성 확보를 위해 fixed Instant를 주입할 수 있도록 컨텍스트에서
     * 시간을 받는다. 활동 코드 안에서 {@code Instant.now()} 직접 호출하지 말고 본 메서드 사용.
     *
     * @return 현재 시간 (구현체 결정)
     */
    default Instant now() { return Instant.now(); }

    /**
     * 엔진에 다음 활동 실행 의도를 전달한다 — 활동이 정적 DAG 외에 동적으로 다음 단계를 결정할 때.
     *
     * <p>구현체는 본 호출의 사이드 이펙트를 내부에 저장만 하고, 실제 dispatch는 활동 메서드가 정상 종료된 뒤
     * 호스트의 TaskExecutor가 수행한다. 호출 후에도 활동 메서드는 정상 반환해야 하며, 본 메서드 호출 자체가
     * dispatch를 의미하지는 않는다.</p>
     *
     * <p>같은 활동 내에서 여러 번 호출되면 마지막 호출이 이긴다 — 이전 호출은 덮어쓰임. mutator로서 thread
     * 안전성을 보장하지 않으므로 활동 메서드 내에서 단일 스레드 호출 가정.</p>
     *
     * @param activityName 다음에 실행할 활동 이름 (등록된 활동이어야 함)
     * @param input 다음 활동 입력 (JSON 직렬화 가능한 POJO)
     */
    void setNext(String activityName, Object input);

    /**
     * {@link #setNext}로 설정된 다음 활동 이름.
     *
     * @return 설정된 이름, 호출 안 됐으면 empty
     */
    Optional<String> nextActivityName();

    /**
     * {@link #setNext}로 설정된 다음 활동 입력.
     *
     * @return 설정된 입력, 호출 안 됐으면 empty
     */
    Optional<Object> nextActivityInput();

    /**
     * 체크포인트 스냅샷을 저장한다 — 활동이 도중에 실패해 재시도될 때 {@link #loadState}로 다시
     * 읽을 수 있는 단일 슬롯.
     *
     * <p>구현체는 JSON 등으로 직렬화해 영속화할 수 있다. 같은 활동 내에서 여러 번 호출하면 마지막이 이긴다.
     * 실제 영속화 시점은 활동 메서드 정상 종료 후.</p>
     *
     * @param stateSnapshot 직렬화 가능한 임의의 스냅샷 객체
     */
    void saveState(Object stateSnapshot);

    /**
     * 저장된 스냅샷 조회. {@link #saveState}가 호출된 적 없거나 호스트가 저장 못 했으면 empty.
     *
     * @return 스냅샷 (구현체별 직렬화 형태 — raw POJO 또는 JSON 문자열)
     */
    Optional<Object> loadState();
}
