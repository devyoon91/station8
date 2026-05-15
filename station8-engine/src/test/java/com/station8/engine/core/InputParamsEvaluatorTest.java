package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #259 — {@link InputParamsEvaluator} 단위 테스트.
 *
 * <p>실제 {@link ExpressionEvaluator} + {@link LineContextBindings}와 end-to-end로 결합해
 * 사용자 노드 입력의 표현식이 어떻게 풀리는지 검증한다.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InputParamsEvaluatorTest {

    private final JsonUtil jsonUtil = new JsonUtil();
    private ExpressionEvaluator evaluator;
    private LineContextBindings bindings;
    private InputParamsEvaluator inputParamsEvaluator;

    @BeforeAll
    void setUp() {
        evaluator = new ExpressionEvaluator();
        bindings = new LineContextBindings(jsonUtil);
        inputParamsEvaluator = new InputParamsEvaluator(evaluator, bindings, jsonUtil);
    }

    @AfterAll
    void tearDown() {
        evaluator.close();
    }

    private DefaultLineContext ctx(Object input, Object prevOutput) {
        return new DefaultLineContext("inst-1", "MY_LINE", "MY_ACTIVITY", 1, input, prevOutput, jsonUtil);
    }

    // ---- 스킵 경로 (정적 입력은 건드리지 않음) ----

    @Test
    void evaluate_nullInput_returnsNull() throws Exception {
        assertThat(inputParamsEvaluator.evaluate(null, ctx(null, null))).isNull();
    }

    @Test
    void evaluate_noExpression_returnsAsIs() throws Exception {
        // 정적 String → 그대로 반환 (JSON 파싱조차 안 함)
        assertThat(inputParamsEvaluator.evaluate("plain text", ctx(null, null))).isEqualTo("plain text");
        assertThat(inputParamsEvaluator.evaluate("{\"x\":1}", ctx(null, null))).isEqualTo("{\"x\":1}");
        assertThat(inputParamsEvaluator.evaluate("", ctx(null, null))).isEqualTo("");
    }

    // ---- String 모드 (JSON 형태 아님) ----

    @Test
    void evaluate_plainStringWithExpression_interpolated() throws Exception {
        DefaultLineContext c = ctx(Map.of("user", "alice"), null);
        assertThat(inputParamsEvaluator.evaluate("Hello {{ $ctx.input.user }}!", c))
                .isEqualTo("Hello alice!");
    }

    @Test
    void evaluate_singleExpressionAlone_stringified() throws Exception {
        // 단일 표현식 + 활동 메서드는 String을 받으므로 toString 적용
        DefaultLineContext c = ctx(null, null);
        assertThat(inputParamsEvaluator.evaluate("{{ 42 }}", c)).isEqualTo("42");
    }

    // ---- JSON 모드 (타입 보존) ----

    @Test
    void evaluate_jsonObjectWithExpression_preservesTypes() throws Exception {
        // 사용자가 input을 {"count": "{{ 1 + 1 }}"} 로 정의
        // 평가 후: {"count": 2}  ← number 보존, string 강제 변환 X
        DefaultLineContext c = ctx(null, null);
        String result = inputParamsEvaluator.evaluate("{\"count\":\"{{ 1 + 1 }}\"}", c);
        assertThat(result).isEqualTo("{\"count\":2}");
    }

    @Test
    void evaluate_jsonObjectMultipleFields_allEvaluated() throws Exception {
        DefaultLineContext c = ctx(Map.of("user", "alice"), "{\"id\":42}");
        String result = inputParamsEvaluator.evaluate(
                "{\"user\":\"{{ $ctx.input.user }}\",\"prevId\":\"{{ $prev.json.id }}\"}", c);
        assertThat(result).isEqualTo("{\"user\":\"alice\",\"prevId\":42}");
    }

    @Test
    void evaluate_jsonNestedStructure_recursiveEvaluation() throws Exception {
        DefaultLineContext c = ctx(null, "{\"id\":7}");
        // nested object + array — 모든 leaf string 순회
        String result = inputParamsEvaluator.evaluate(
                "{\"meta\":{\"prevId\":\"{{ $prev.json.id }}\"},\"items\":[\"{{ 'a' + 'b' }}\",\"static\"]}",
                c);
        assertThat(result).isEqualTo("{\"meta\":{\"prevId\":7},\"items\":[\"ab\",\"static\"]}");
    }

    @Test
    void evaluate_jsonObjectExpressionReturnsObject_substitutesAsObject() throws Exception {
        // $prev.json 자체를 반환 — Map으로 추출되어 JSON 객체로 직렬화
        DefaultLineContext c = ctx(null, "{\"x\":1,\"y\":2}");
        String result = inputParamsEvaluator.evaluate("{\"echo\":\"{{ $prev.json }}\"}", c);
        assertThat(result).isEqualTo("{\"echo\":{\"x\":1,\"y\":2}}");
    }

    @Test
    void evaluate_jsonObjectStaticFieldsUntouched() throws Exception {
        // 표현식 없는 필드는 손대지 않음
        DefaultLineContext c = ctx(null, null);
        String result = inputParamsEvaluator.evaluate(
                "{\"static\":\"hello\",\"computed\":\"{{ 1 + 1 }}\"}", c);
        assertThat(result).isEqualTo("{\"static\":\"hello\",\"computed\":2}");
    }

    @Test
    void evaluate_jsonArray_leafEvaluated() throws Exception {
        DefaultLineContext c = ctx(Map.of("name", "alice"), null);
        String result = inputParamsEvaluator.evaluate(
                "[\"{{ $ctx.input.name }}\",\"static\",\"{{ 1 + 2 }}\"]", c);
        assertThat(result).isEqualTo("[\"alice\",\"static\",3]");
    }

    @Test
    void evaluate_jsonStringTextWithExpression_interpolated() throws Exception {
        // {{ }}와 정적 텍스트 혼재된 string 필드 — 결과는 string join
        DefaultLineContext c = ctx(Map.of("user", "alice"), null);
        String result = inputParamsEvaluator.evaluate(
                "{\"greeting\":\"Hello {{ $ctx.input.user }}!\"}", c);
        assertThat(result).isEqualTo("{\"greeting\":\"Hello alice!\"}");
    }

    @Test
    void evaluate_invalidJsonFallsBackToStringMode() throws Exception {
        // {로 시작하지만 JSON 파싱 실패 → string mode
        DefaultLineContext c = ctx(null, null);
        String result = inputParamsEvaluator.evaluate("{not valid {{ 1 + 1 }}", c);
        // string 모드: 마지막 }가 닫히지 않은 {{ 와 매치되어 표현식으로 평가됨
        assertThat(result).isEqualTo("{not valid 2");
    }

    // ---- 평가 실패 → 예외 위로 전파 ----

    @Test
    void evaluate_jsonExpressionEvalFails_throws() {
        DefaultLineContext c = ctx(null, null);
        assertThatThrownBy(() -> inputParamsEvaluator.evaluate(
                "{\"x\":\"{{ unknownVar }}\"}", c))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void evaluate_stringExpressionEvalFails_throws() {
        DefaultLineContext c = ctx(null, null);
        assertThatThrownBy(() -> inputParamsEvaluator.evaluate(
                "result={{ unknownVar }}", c))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    // ---- 정적 JSON은 손대지 않음 (회귀 가드) ----

    @Test
    void evaluate_jsonWithNoExpression_skippedEntirely() throws Exception {
        // 표현식 0개 → 빠른 스킵 경로 (JSON 파싱조차 안 일어남)
        // 동일 JSON이 그대로 반환됨 (Jackson re-serialization 안 거침)
        DefaultLineContext c = ctx(null, null);
        String input = "{\"a\":1,\"b\":\"hello\",\"c\":[1,2,3]}";
        assertThat(inputParamsEvaluator.evaluate(input, c)).isSameAs(input);
    }
}
