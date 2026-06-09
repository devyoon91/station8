package com.station8.engine.test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@link InMemoryLineContext} 의 fluent builder (#319).
 *
 * <p>플러그인 단위 테스트에서 컨텍스트의 일부만 의미 있고 나머지는 default가 적절한 패턴을 지원.</p>
 *
 * <h3>예시</h3>
 * <pre>{@code
 *   InMemoryLineContext ctx = LineContextBuilder.create()
 *       .workflowName("OrderFlow")
 *       .currentActivityName("VALIDATE")
 *       .input(Map.of("orderId", "123"))
 *       .attempt(2)                              // 재시도 케이스
 *       .runtimeParams(Map.of("date", "2026-05-22"))
 *       .build();
 * }</pre>
 *
 * <p>모든 setter는 빌더를 반환하므로 chain 가능. {@link #build} 가 {@link InMemoryLineContext} 인스턴스 생성.</p>
 */
public final class LineContextBuilder {

    String instanceId = "test-" + UUID.randomUUID();
    String workflowName = "test-workflow";
    String currentActivityName = "test-activity";
    String nodeId = null;
    int attempt = 1;
    Object input = null;
    Object previousOutput = null;
    Map<String, Object> attributes = new HashMap<>();
    Map<String, String> runtimeParams = new HashMap<>();
    Instant now = Instant.now();
    int itemIndex = 0;
    Object item = null;
    Object items = null;

    private LineContextBuilder() {}

    /**
     * 새 빌더 인스턴스. 모든 필드가 기본값으로 초기화되어 있어 즉시 {@link #build} 호출 가능.
     *
     * @return 새 빌더
     */
    public static LineContextBuilder create() {
        return new LineContextBuilder();
    }

    /**
     * 라인 인스턴스 식별자. 기본값은 {@code "test-" + UUID}.
     *
     * @param value 인스턴스 ID
     * @return 본 빌더
     */
    public LineContextBuilder instanceId(String value) {
        this.instanceId = value;
        return this;
    }

    /**
     * 워크플로우(라인) 이름. 기본값 {@code "test-workflow"}.
     *
     * @param value 라인 이름
     * @return 본 빌더
     */
    public LineContextBuilder workflowName(String value) {
        this.workflowName = value;
        return this;
    }

    /**
     * 현재 실행 중인 액티비티 이름. 기본값 {@code "test-activity"}.
     *
     * @param value 액티비티 이름
     * @return 본 빌더
     */
    public LineContextBuilder currentActivityName(String value) {
        this.currentActivityName = value;
        return this;
    }

    /**
     * 현재 활동의 DAG 노드 ID. 기본값 null (linear 모드).
     *
     * @param value 노드 ID
     * @return 본 빌더
     */
    public LineContextBuilder nodeId(String value) {
        this.nodeId = value;
        return this;
    }

    /**
     * 시도 횟수. 1 = 최초 실행, 2+ = 재시도. 기본값 1.
     *
     * @param value 시도 횟수 (1 이상)
     * @return 본 빌더
     */
    public LineContextBuilder attempt(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("attempt는 1 이상이어야 한다: " + value);
        }
        this.attempt = value;
        return this;
    }

    /**
     * 액티비티 입력 객체. 기본값 null.
     *
     * @param value 입력
     * @return 본 빌더
     */
    public LineContextBuilder input(Object value) {
        this.input = value;
        return this;
    }

    /**
     * 이전 액티비티 출력. 기본값 null (이전 활동 없음).
     *
     * @param value 이전 출력
     * @return 본 빌더
     */
    public LineContextBuilder previousOutput(Object value) {
        this.previousOutput = value;
        return this;
    }

    /**
     * 컨텍스트 부가 속성 맵. 기존 attributes를 덮어쓴다.
     *
     * @param value 속성 맵
     * @return 본 빌더
     */
    public LineContextBuilder attributes(Map<String, Object> value) {
        this.attributes = new HashMap<>(value);
        return this;
    }

    /**
     * 속성 하나 추가/갱신.
     *
     * @param key 키
     * @param value 값
     * @return 본 빌더
     */
    public LineContextBuilder attribute(String key, Object value) {
        this.attributes.put(key, value);
        return this;
    }

    /**
     * runtime params 맵 — 즉시 실행 시점 사용자 입력. 기존 값을 덮어쓴다.
     *
     * @param value runtime params 맵
     * @return 본 빌더
     */
    public LineContextBuilder runtimeParams(Map<String, String> value) {
        this.runtimeParams = new HashMap<>(value);
        return this;
    }

    /**
     * runtime param 하나 추가/갱신.
     *
     * @param key 키
     * @param value 값
     * @return 본 빌더
     */
    public LineContextBuilder runtimeParam(String key, String value) {
        this.runtimeParams.put(key, value);
        return this;
    }

    /**
     * 컨텍스트의 now() 가 반환할 시간. 시간 의존 액티비티 테스트의 결정성 확보용.
     *
     * @param value 고정 시간
     * @return 본 빌더
     */
    public LineContextBuilder now(Instant value) {
        this.now = value;
        return this;
    }

    /**
     * M22 fan-out 레인 정보 — {@code $item} / {@code $items} / {@code $itemIndex} 표현식 테스트용.
     *
     * @param index 레인 인덱스 (≥ 0)
     * @param item 현재 원소
     * @param items 레인이 비롯된 전체 배열
     * @return 본 빌더
     */
    public LineContextBuilder itemContext(int index, Object item, Object items) {
        this.itemIndex = index;
        this.item = item;
        this.items = items;
        return this;
    }

    /**
     * 빌더 설정으로 {@link InMemoryLineContext} 생성.
     *
     * @return 새 컨텍스트
     */
    public InMemoryLineContext build() {
        return new InMemoryLineContext(this);
    }
}
