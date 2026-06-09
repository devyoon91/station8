package com.station8.engine.test;

import com.station8.engine.core.LineContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 플러그인 단위 테스트용 {@link LineContext} 메모리-only 구현체 (#319).
 *
 * <p>Mockito 만으로는 검증 못 하는 사이드 이펙트({@link #setNext}, {@link #saveState})를
 * 내부에 캡쳐해서 테스트에서 단언할 수 있게 한다. {@link LineContextBuilder}로 생성하는 게 자연스럽다.</p>
 *
 * <h3>예시</h3>
 * <pre>{@code
 *   InMemoryLineContext ctx = LineContextBuilder.create()
 *       .runtimeParams(Map.of("date", "2026-05-22"))
 *       .build();
 *
 *   plugin.run("{}", ctx);
 *
 *   // 사이드 이펙트 단언
 *   assertEquals("nextActivity", ctx.capturedNextActivityName().orElseThrow());
 *   assertEquals(reportSummary, ctx.savedState().orElseThrow());
 * }</pre>
 *
 * <p>본 클래스는 thread-safe하지 않다 — 플러그인 단위 테스트는 단일 스레드 가정.</p>
 */
public final class InMemoryLineContext implements LineContext {

    private final String instanceId;
    private final String workflowName;
    private final String currentActivityName;
    private final String nodeId;
    private final int attempt;
    private final Object input;
    private final Object previousOutput;
    private final Map<String, Object> attributes;
    private final Map<String, String> runtimeParams;
    private final Instant now;
    private final int itemIndex;       // M22 fan-out 레인 인덱스
    private final Object item;         // M22 현재 원소
    private final Object items;        // M22 전체 배열

    // setNext / saveState로 캡쳐된 사이드 이펙트
    private String capturedNextActivityName;
    private Object capturedNextActivityInput;
    private Object savedState;

    InMemoryLineContext(LineContextBuilder b) {
        this.instanceId = b.instanceId;
        this.workflowName = b.workflowName;
        this.currentActivityName = b.currentActivityName;
        this.nodeId = b.nodeId;
        this.attempt = b.attempt;
        this.input = b.input;
        this.previousOutput = b.previousOutput;
        this.attributes = new LinkedHashMap<>(b.attributes);
        this.runtimeParams = new LinkedHashMap<>(b.runtimeParams);
        this.now = b.now;
        this.itemIndex = b.itemIndex;
        this.item = b.item;
        this.items = b.items;
    }

    @Override public String instanceId() { return instanceId; }
    @Override public String workflowName() { return workflowName; }
    @Override public String currentActivityName() { return currentActivityName; }
    @Override public String nodeId() { return nodeId; }
    @Override public int attempt() { return attempt; }
    @Override public Object input() { return input; }
    @Override public Optional<Object> previousOutput() { return Optional.ofNullable(previousOutput); }
    @Override public Map<String, Object> attributes() { return attributes; }
    @Override public Map<String, String> runtimeParams() { return runtimeParams; }
    @Override public Instant now() { return now; }
    @Override public int itemIndex() { return itemIndex; }
    @Override public Optional<Object> item() { return Optional.ofNullable(item); }
    @Override public Optional<Object> items() { return Optional.ofNullable(items); }

    @Override
    public void setNext(String activityName, Object nextInput) {
        this.capturedNextActivityName = activityName;
        this.capturedNextActivityInput = nextInput;
    }

    @Override
    public Optional<String> nextActivityName() {
        return Optional.ofNullable(capturedNextActivityName);
    }

    @Override
    public Optional<Object> nextActivityInput() {
        return Optional.ofNullable(capturedNextActivityInput);
    }

    @Override
    public void saveState(Object stateSnapshot) {
        this.savedState = stateSnapshot;
    }

    @Override
    public Optional<Object> loadState() {
        return Optional.ofNullable(savedState);
    }

    /**
     * {@link #setNext}로 마지막에 지정된 다음 액티비티 이름. 호출 안 됐으면 empty.
     *
     * <p>{@link #nextActivityName()} 과 동일하지만 의도(테스트가 사이드 이펙트를 검증)를
     * 더 명확히 드러내는 별칭.</p>
     *
     * @return 마지막 setNext의 activityName, 없으면 empty
     */
    public Optional<String> capturedNextActivityName() {
        return nextActivityName();
    }

    /**
     * {@link #setNext}로 마지막에 지정된 다음 액티비티 입력. 호출 안 됐으면 empty.
     *
     * @return 마지막 setNext의 input, 없으면 empty
     */
    public Optional<Object> capturedNextActivityInput() {
        return nextActivityInput();
    }

    /**
     * {@link #saveState}로 마지막에 저장된 스냅샷. 호출 안 됐으면 empty.
     *
     * @return 마지막 saveState의 스냅샷, 없으면 empty
     */
    public Optional<Object> savedState() {
        return Optional.ofNullable(savedState);
    }

    /**
     * 현재까지 입력된 attributes의 mutable 뷰. 테스트가 직접 put/get 가능.
     *
     * @return attributes 맵 (mutable, 본 객체의 내부 상태)
     */
    public Map<String, Object> mutableAttributes() {
        return attributes;
    }

    /**
     * 캡쳐된 사이드 이펙트 초기화. 동일 컨텍스트로 여러 시나리오 검증할 때.
     */
    public void resetCapturedSideEffects() {
        this.capturedNextActivityName = null;
        this.capturedNextActivityInput = null;
        this.savedState = null;
    }

    /**
     * 최소 정보만 채운 컨텍스트의 빠른 생성. 더 자세한 설정이 필요하면 {@link LineContextBuilder} 사용.
     *
     * @param input 액티비티 입력
     * @return 단순 컨텍스트 (workflow / activity 이름 등은 placeholder)
     */
    public static InMemoryLineContext withInput(Object input) {
        return LineContextBuilder.create().input(input).build();
    }
}
