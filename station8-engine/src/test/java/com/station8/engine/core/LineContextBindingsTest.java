package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #258 — {@link LineContextBindings} 단위 테스트.
 *
 * <p>실제 {@link ExpressionEvaluator}와 end-to-end로 결합해 검증 — binding 변환 로직과
 * sandbox 통과 여부를 한 번에 본다.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LineContextBindingsTest {

    private final JsonUtil jsonUtil = new JsonUtil();
    private final LineContextBindings bindings = new LineContextBindings(jsonUtil);
    private ExpressionEvaluator evaluator;

    @BeforeAll
    void setUp() {
        evaluator = new ExpressionEvaluator();
    }

    @AfterAll
    void tearDown() {
        evaluator.close();
    }

    private DefaultLineContext ctx(Object input, Object prevOutput) {
        return new DefaultLineContext("inst-1", "MY_LINE", "MY_ACTIVITY", 1, input, prevOutput, jsonUtil);
    }

    // ---- $prev ----

    @Test
    void prev_jsonStringPreviousOutput_parsedToObject() throws Exception {
        DefaultLineContext c = ctx(null, "{\"id\":42,\"name\":\"foo\"}");
        Map<String, Object> b = bindings.from(c);
        assertThat(evaluator.evaluate("{{ $prev.json.id }}", b)).isEqualTo(42L);
        assertThat(evaluator.evaluate("{{ $prev.json.name }}", b)).isEqualTo("foo");
    }

    @Test
    void prev_mapPreviousOutput_directProxy() throws Exception {
        DefaultLineContext c = ctx(null, Map.of("status", "OK"));
        assertThat(evaluator.evaluate("{{ $prev.json.status }}", bindings.from(c))).isEqualTo("OK");
    }

    @Test
    void prev_listPreviousOutput_indexAccess() throws Exception {
        DefaultLineContext c = ctx(null, List.of("alpha", "beta"));
        Map<String, Object> b = bindings.from(c);
        assertThat(evaluator.evaluate("{{ $prev.json[0] }}", b)).isEqualTo("alpha");
        assertThat(evaluator.evaluate("{{ $prev.json[1] }}", b)).isEqualTo("beta");
        assertThat(evaluator.evaluate("{{ $prev.json.length }}", b)).isEqualTo(2L);
    }

    @Test
    void prev_nullPreviousOutput_jsonIsNull() throws Exception {
        DefaultLineContext c = ctx(null, null);
        assertThat(evaluator.evaluate("{{ $prev.json }}", bindings.from(c))).isNull();
    }

    @Test
    void prev_rawStringPreviousOutput_exposedAsString() throws Exception {
        DefaultLineContext c = ctx(null, "OK");
        // JSON 형태 아님 → raw string 그대로
        assertThat(evaluator.evaluate("{{ $prev.json }}", bindings.from(c))).isEqualTo("OK");
        assertThat(evaluator.evaluate("{{ $prev.json == 'OK' }}", bindings.from(c))).isEqualTo(true);
    }

    @Test
    void prev_invalidJsonString_fallbackToRaw() throws Exception {
        DefaultLineContext c = ctx(null, "{not valid json");
        // 파싱 실패 → raw string 보존
        assertThat(evaluator.evaluate("{{ $prev.json }}", bindings.from(c))).isEqualTo("{not valid json");
    }

    @Test
    void prev_binaryPlaceholder_emptyObject() throws Exception {
        DefaultLineContext c = ctx(null, null);
        // 임의 키 액세스 → undefined → null
        assertThat(evaluator.evaluate("{{ $prev.binary.x }}", bindings.from(c))).isNull();
    }

    @Test
    void prev_nestedJsonStructure_recursiveAccess() throws Exception {
        DefaultLineContext c = ctx(null, "{\"items\":[{\"id\":1},{\"id\":2}]}");
        Map<String, Object> b = bindings.from(c);
        assertThat(evaluator.evaluate("{{ $prev.json.items[0].id }}", b)).isEqualTo(1L);
        assertThat(evaluator.evaluate("{{ $prev.json.items[1].id }}", b)).isEqualTo(2L);
        assertThat(evaluator.evaluate("{{ $prev.json.items.length }}", b)).isEqualTo(2L);
    }

    // ---- $ctx.input ----

    @Test
    void ctx_inputMap_accessible() throws Exception {
        DefaultLineContext c = ctx(Map.of("user", "alice"), null);
        assertThat(evaluator.evaluate("{{ $ctx.input.user }}", bindings.from(c))).isEqualTo("alice");
    }

    @Test
    void ctx_inputJsonString_parsed() throws Exception {
        DefaultLineContext c = ctx("{\"x\":10}", null);
        assertThat(evaluator.evaluate("{{ $ctx.input.x }}", bindings.from(c))).isEqualTo(10L);
    }

    @Test
    void ctx_inputRawString_exposedAsString() throws Exception {
        DefaultLineContext c = ctx("plain text", null);
        assertThat(evaluator.evaluate("{{ $ctx.input }}", bindings.from(c))).isEqualTo("plain text");
    }

    @Test
    void ctx_inputNull_returnsNull() throws Exception {
        DefaultLineContext c = ctx(null, null);
        assertThat(evaluator.evaluate("{{ $ctx.input }}", bindings.from(c))).isNull();
    }

    // ---- $ctx.run ----

    @Test
    void ctx_runMeta_exposed() throws Exception {
        DefaultLineContext c = ctx(null, null);
        Map<String, Object> b = bindings.from(c);
        assertThat(evaluator.evaluate("{{ $ctx.run.id }}", b)).isEqualTo("inst-1");
        assertThat(evaluator.evaluate("{{ $ctx.run.attempt }}", b)).isEqualTo(1L);
    }

    // ---- $ctx.line ----

    @Test
    void ctx_lineMeta_exposed() throws Exception {
        DefaultLineContext c = ctx(null, null);
        Map<String, Object> b = bindings.from(c);
        assertThat(evaluator.evaluate("{{ $ctx.line.name }}", b)).isEqualTo("MY_LINE");
        assertThat(evaluator.evaluate("{{ $ctx.line.activity }}", b)).isEqualTo("MY_ACTIVITY");
    }

    // ---- $ctx.runtime (#134) ----

    @Test
    void ctx_runtimeParams_exposed() throws Exception {
        DefaultLineContext c = ctx(null, null);
        c.setRuntimeParams(Map.of("date", "2026-05-15", "env", "prod"));
        Map<String, Object> b = bindings.from(c);
        assertThat(evaluator.evaluate("{{ $ctx.runtime.date }}", b)).isEqualTo("2026-05-15");
        assertThat(evaluator.evaluate("{{ $ctx.runtime.env }}", b)).isEqualTo("prod");
    }

    @Test
    void ctx_runtimeParamsEmpty_undefinedKeysAreNull() throws Exception {
        DefaultLineContext c = ctx(null, null);
        // setRuntimeParams 호출 안 함 — 기본 Map.of()
        assertThat(evaluator.evaluate("{{ $ctx.runtime.absent }}", bindings.from(c))).isNull();
    }

    // ---- $credentials placeholder ----

    @Test
    void credentials_placeholder_undefinedKeysAreNull() throws Exception {
        DefaultLineContext c = ctx(null, null);
        assertThat(evaluator.evaluate("{{ $credentials.foo }}", bindings.from(c))).isNull();
    }

    // ---- Sandbox: ProxyObject blocks Java method access ----

    @Test
    void prev_javaReflectionAttempt_blocked() {
        DefaultLineContext c = ctx(null, "{\"x\":1}");
        // ProxyObject는 Java getClass 노출 안 함 → undefined.x() 호출 → TypeError
        assertThatThrownBy(() -> evaluator.evaluate("{{ $prev.json.getClass().getName() }}", bindings.from(c)))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void ctx_javaToStringAttempt_blocked() {
        DefaultLineContext c = ctx(null, null);
        // ProxyObject의 toString은 JS Object.prototype.toString로 처리됨 (안전)
        // 하지만 명시적으로 Java 객체 메서드 escape 시도 — 차단 확인
        assertThatThrownBy(() -> evaluator.evaluate("{{ $ctx.run.id.getClass() }}", bindings.from(c)))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    // ---- 혼재 string join (n8n 호환) ----

    @Test
    void textTemplateWithBinding_joinsAsString() throws Exception {
        DefaultLineContext c = ctx(Map.of("user", "alice"), null);
        assertThat(evaluator.evaluate("Hello {{ $ctx.input.user }}!", bindings.from(c)))
                .isEqualTo("Hello alice!");
    }

    @Test
    void multiBindingTemplate_joinsAsString() throws Exception {
        DefaultLineContext c = ctx(Map.of("user", "alice"), "{\"id\":42}");
        Map<String, Object> b = bindings.from(c);
        assertThat(evaluator.evaluate("user={{ $ctx.input.user }} prev={{ $prev.json.id }}", b))
                .isEqualTo("user=alice prev=42");
    }

    // ---- bindings 구조 자체 확인 (단위 검증) ----

    @Test
    void from_returnsAllThreeBindings() {
        DefaultLineContext c = ctx(null, null);
        Map<String, Object> b = bindings.from(c);
        assertThat(b).containsOnlyKeys(LineContextBindings.PREV, LineContextBindings.CTX,
                LineContextBindings.CREDENTIALS);
    }

    @Test
    void toJsExposable_preservesNestedMapAndList() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("n", 1);
        input.put("list", List.of("a", "b"));
        Object exposed = bindings.toJsExposable(input);
        // ProxyObject 타입 확인 — Java Map이 그대로 노출되지 않음 (sandbox 우회 차단)
        assertThat(exposed).isInstanceOf(org.graalvm.polyglot.proxy.ProxyObject.class);
    }
}
