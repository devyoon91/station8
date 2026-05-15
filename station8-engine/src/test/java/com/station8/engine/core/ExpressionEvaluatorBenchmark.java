package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #261 — M16 표현식 평가 latency 마이크로벤치마크.
 *
 * <p>JUnit 기반 단순 벤치마크. JMH가 아니라 정확도는 ±1ms 수준 — 의사결정에 충분한 정밀도.
 * RFC #255의 "이미지 크기 측정 결과가 나쁘면 plugin 분리" 트리거 결정 데이터를 만든다.</p>
 *
 * <h3>실행</h3>
 * <pre>
 *   ./gradlew :station8-engine:perfTest
 * </pre>
 *
 * <p>일반 {@code test} task에선 {@code @Tag("perf")}로 제외 — CI 시간 보호.</p>
 *
 * <h3>측정 시나리오</h3>
 * <ul>
 *   <li>Cold start — 첫 평가 (Engine 초기화 후 1회)</li>
 *   <li>Static skip — 표현식 없는 입력 (회귀 가드: 정적 노드 입력은 오버헤드 0이어야 함)</li>
 *   <li>Single expression — {@code {{ 1 + 1 }}}</li>
 *   <li>Binding access — {@code {{ $ctx.input.x }}}</li>
 *   <li>Nested binding — {@code {{ $prev.json.items[0].id }}}</li>
 *   <li>String interpolation — {@code "Hello {{ name }}!"}</li>
 *   <li>JSON mode — {@code {"x": "{{ $prev.json.x }}"}}</li>
 * </ul>
 *
 * <p>결과는 stdout으로 출력. {@code docs/decisions/m16-expression-engine.md}의 "측정 결과"
 * 섹션에 정리.</p>
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpressionEvaluatorBenchmark {

    private static final int WARMUP_ITER = 100;
    private static final int MEASURE_ITER = 1000;

    private final JsonUtil jsonUtil = new JsonUtil();
    private ExpressionEvaluator evaluator;
    private LineContextBindings bindingsFactory;
    private InputParamsEvaluator inputParamsEvaluator;

    @BeforeAll
    void setUp() {
        evaluator = new ExpressionEvaluator();
        bindingsFactory = new LineContextBindings(jsonUtil);
        inputParamsEvaluator = new InputParamsEvaluator(evaluator, bindingsFactory, jsonUtil);
    }

    @AfterAll
    void tearDown() {
        evaluator.close();
    }

    @Test
    void benchmark_evaluator_coldStart_singleExpression() throws Exception {
        // 본 메서드는 Engine 초기화 후 첫 평가 — JIT/Truffle 워밍업 비용 포함.
        // 같은 ExpressionEvaluator 인스턴스를 다른 벤치 메서드와 공유하면 측정 의미 X.
        // 별도 evaluator로 cold 측정.
        try (ExpressionEvaluator cold = new ExpressionEvaluator()) {
            long t0 = System.nanoTime();
            cold.evaluate("{{ 1 + 1 }}", Map.of());
            long elapsed = System.nanoTime() - t0;
            report("cold-start single expr", 1, elapsed);
        }
    }

    @Test
    void benchmark_evaluator_staticSkipPath() throws Exception {
        warmup(() -> evaluator.evaluate("plain text no expr", Map.of()));
        long elapsed = measure(() -> evaluator.evaluate("plain text no expr", Map.of()));
        report("static-skip (no {{)", MEASURE_ITER, elapsed);
    }

    @Test
    void benchmark_evaluator_singleExpression() throws Exception {
        Map<String, Object> b = Map.of();
        warmup(() -> evaluator.evaluate("{{ 1 + 1 }}", b));
        long elapsed = measure(() -> evaluator.evaluate("{{ 1 + 1 }}", b));
        report("single expr {{ 1+1 }}", MEASURE_ITER, elapsed);
    }

    @Test
    void benchmark_evaluator_bindingAccess() throws Exception {
        Map<String, Object> b = bindingsFactory.from(
                ctx(Map.of("x", 42), null));
        warmup(() -> evaluator.evaluate("{{ $ctx.input.x }}", b));
        long elapsed = measure(() -> evaluator.evaluate("{{ $ctx.input.x }}", b));
        report("binding access {{ $ctx.input.x }}", MEASURE_ITER, elapsed);
    }

    @Test
    void benchmark_evaluator_nestedBindingAccess() throws Exception {
        Map<String, Object> b = bindingsFactory.from(
                ctx(null, "{\"items\":[{\"id\":1},{\"id\":2}]}"));
        warmup(() -> evaluator.evaluate("{{ $prev.json.items[0].id }}", b));
        long elapsed = measure(() -> evaluator.evaluate("{{ $prev.json.items[0].id }}", b));
        report("nested {{ $prev.json.items[0].id }}", MEASURE_ITER, elapsed);
    }

    @Test
    void benchmark_evaluator_stringInterpolation() throws Exception {
        Map<String, Object> b = bindingsFactory.from(
                ctx(Map.of("name", "alice"), null));
        warmup(() -> evaluator.evaluate("Hello {{ $ctx.input.name }}!", b));
        long elapsed = measure(() -> evaluator.evaluate("Hello {{ $ctx.input.name }}!", b));
        report("string interp 'Hello {{ x }}!'", MEASURE_ITER, elapsed);
    }

    @Test
    void benchmark_inputParams_jsonModeSimple() throws Exception {
        DefaultLineContext c = ctx(Map.of("user", "alice"), null);
        warmup(() -> inputParamsEvaluator.evaluate("{\"u\":\"{{ $ctx.input.user }}\"}", c));
        long elapsed = measure(() -> inputParamsEvaluator.evaluate("{\"u\":\"{{ $ctx.input.user }}\"}", c));
        report("inputParams JSON simple", MEASURE_ITER, elapsed);
    }

    @Test
    void benchmark_inputParams_staticJson_skipsParse() throws Exception {
        DefaultLineContext c = ctx(null, null);
        String input = "{\"a\":1,\"b\":\"hello\",\"c\":[1,2,3]}";
        warmup(() -> inputParamsEvaluator.evaluate(input, c));
        long elapsed = measure(() -> inputParamsEvaluator.evaluate(input, c));
        report("inputParams static JSON (skip)", MEASURE_ITER, elapsed);
    }

    // ---- helpers ----

    private DefaultLineContext ctx(Object input, Object prevOutput) {
        return new DefaultLineContext("inst-1", "MY_LINE", "MY_ACTIVITY", 1,
                input, prevOutput, jsonUtil);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void warmup(ThrowingRunnable r) throws Exception {
        for (int i = 0; i < WARMUP_ITER; i++) r.run();
    }

    private long measure(ThrowingRunnable r) throws Exception {
        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE_ITER; i++) r.run();
        return System.nanoTime() - t0;
    }

    private void report(String label, int iter, long elapsedNanos) {
        double totalMs = elapsedNanos / 1_000_000.0;
        double perOpUs = (elapsedNanos / (double) iter) / 1_000.0;
        System.out.printf("%-44s | %4d ops | total %8.2f ms | per-op %8.2f us%n",
                label, iter, totalMs, perOpUs);
    }
}
