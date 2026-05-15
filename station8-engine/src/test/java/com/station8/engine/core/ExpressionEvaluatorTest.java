package com.station8.engine.core;

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
 * #257 — {@link ExpressionEvaluator} 단위 테스트.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>정적 텍스트 / 단일 표현식 / 혼재 모드 — 반환 타입 보존 규칙</li>
 *   <li>bindings로 외부 값 노출</li>
 *   <li>sandbox 우회 시도 차단 (HostAccess.NONE / IO / load / console)</li>
 *   <li>평가 실패 → ExpressionEvaluationException 격하</li>
 *   <li>parse 헬퍼 동작 (닫히지 않은 {{}})</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpressionEvaluatorTest {

    private ExpressionEvaluator evaluator;

    @BeforeAll
    void setUp() {
        evaluator = new ExpressionEvaluator();
    }

    @AfterAll
    void tearDown() {
        evaluator.close();
    }

    // ---- evaluate: null / static ----

    @Test
    void evaluate_nullTemplate_returnsNull() throws Exception {
        assertThat(evaluator.evaluate(null, Map.of())).isNull();
    }

    @Test
    void evaluate_staticText_returnsAsIs() throws Exception {
        assertThat(evaluator.evaluate("hello world", Map.of())).isEqualTo("hello world");
        assertThat(evaluator.evaluate("", Map.of())).isEqualTo("");
    }

    @Test
    void evaluate_nullBindings_treatedAsEmpty() throws Exception {
        assertThat(evaluator.evaluate("{{ 1 + 1 }}", null)).isEqualTo(2L);
    }

    // ---- evaluate: 단일 표현식 (타입 보존) ----

    @Test
    void evaluate_singleNumberExpr_returnsLong() throws Exception {
        assertThat(evaluator.evaluate("{{ 1 + 1 }}", Map.of())).isEqualTo(2L);
    }

    @Test
    void evaluate_singleDoubleExpr_returnsDouble() throws Exception {
        // JS Number는 단일 타입. fractional → Double, integer-valued → Long.
        assertThat(evaluator.evaluate("{{ 0.5 + 0.25 }}", Map.of())).isEqualTo(0.75d);
    }

    @Test
    void evaluate_singleStringExpr_returnsString() throws Exception {
        assertThat(evaluator.evaluate("{{ 'foo' + 'bar' }}", Map.of())).isEqualTo("foobar");
    }

    @Test
    void evaluate_singleBooleanExpr_returnsBoolean() throws Exception {
        assertThat(evaluator.evaluate("{{ 1 < 2 }}", Map.of())).isEqualTo(true);
        assertThat(evaluator.evaluate("{{ 1 > 2 }}", Map.of())).isEqualTo(false);
    }

    @Test
    void evaluate_singleNullExpr_returnsNull() throws Exception {
        assertThat(evaluator.evaluate("{{ null }}", Map.of())).isNull();
        assertThat(evaluator.evaluate("{{ undefined }}", Map.of())).isNull();
    }

    // ---- evaluate: 혼재 (string join) ----

    @Test
    void evaluate_textWithSingleExpr_joinsAsString() throws Exception {
        assertThat(evaluator.evaluate("Hello {{ 'world' }}!", Map.of())).isEqualTo("Hello world!");
    }

    @Test
    void evaluate_multipleExprs_joinsAsString() throws Exception {
        assertThat(evaluator.evaluate("{{ 1 }}-{{ 2 }}-{{ 3 }}", Map.of())).isEqualTo("1-2-3");
    }

    @Test
    void evaluate_exprNullInJoin_renderedAsEmpty() throws Exception {
        assertThat(evaluator.evaluate("a={{ null }}b", Map.of())).isEqualTo("a=b");
    }

    // ---- evaluate: bindings ----

    @Test
    void evaluate_bindingPrimitive_accessible() throws Exception {
        Map<String, Object> b = Map.of("name", "world");
        assertThat(evaluator.evaluate("{{ name }}", b)).isEqualTo("world");
        assertThat(evaluator.evaluate("Hello {{ name }}!", b)).isEqualTo("Hello world!");
    }

    @Test
    void evaluate_bindingNumber_arithmetic() throws Exception {
        Map<String, Object> b = Map.of("x", 10, "y", 5);
        assertThat(evaluator.evaluate("{{ x + y }}", b)).isEqualTo(15L);
    }

    @Test
    void evaluate_bindingMultipleNames_independentAccess() throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", "alpha");
        b.put("b", "beta");
        assertThat(evaluator.evaluate("{{ a }}-{{ b }}", b)).isEqualTo("alpha-beta");
    }

    // ---- evaluate: sandbox 우회 시도 (반드시 차단) ----

    @Test
    void evaluate_javaTypeAccess_blocked() {
        // GraalVM Java.type() — HostAccess.NONE이면 ReferenceError 또는 평가 예외
        assertThatThrownBy(() -> evaluator.evaluate("{{ Java.type('java.lang.Runtime') }}", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void evaluate_polyglotImport_blocked() {
        // Polyglot.import — allowNativeAccess(false) + HostAccess.NONE
        assertThatThrownBy(() -> evaluator.evaluate("{{ Polyglot.import('foo') }}", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void evaluate_loadFunction_blocked() {
        // js.load=false → load 식별자 자체 미정의 → ReferenceError
        assertThatThrownBy(() -> evaluator.evaluate("{{ load('http://evil') }}", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void evaluate_consoleCall_blocked() {
        // js.console=false → console 미정의
        assertThatThrownBy(() -> evaluator.evaluate("{{ console.log('x') }}", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void evaluate_bindingMethodCall_blocked() {
        // String.toUpperCase는 Java 메서드 — HostAccess.NONE이면 노출되지 않아야 함
        // (GraalVM은 Java String을 JS string proxy로 변환하나, HostAccess.NONE이면 메서드 호출 차단)
        Map<String, Object> b = Map.of("s", "hello");
        // s.length는 JS String의 native 속성 — 통과
        // s.getClass()는 Java reflection — 차단되어야 함
        assertThatThrownBy(() -> evaluator.evaluate("{{ s.getClass().getName() }}", b))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    // ---- evaluate: 평가 실패 ----

    @Test
    void evaluate_syntaxError_throws() {
        assertThatThrownBy(() -> evaluator.evaluate("{{ 1 + }}", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class)
                .hasMessageContaining("표현식 평가 실패");
    }

    @Test
    void evaluate_referenceError_throws() {
        assertThatThrownBy(() -> evaluator.evaluate("{{ unknownVar }}", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void evaluate_runtimeError_throws() {
        // null.x 액세스 — TypeError
        assertThatThrownBy(() -> evaluator.evaluate("{{ null.x }}", Map.of()))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void evaluate_exceptionPreservesExprAndTemplate() {
        try {
            evaluator.evaluate("Hello {{ unknownVar }}!", Map.of());
        } catch (ExpressionEvaluator.ExpressionEvaluationException ex) {
            assertThat(ex.expression()).isEqualTo("unknownVar");
            assertThat(ex.template()).isEqualTo("Hello {{ unknownVar }}!");
            return;
        }
        throw new AssertionError("expected ExpressionEvaluationException");
    }

    // ---- parse: 닫히지 않은 {{ 등 엣지 케이스 ----

    @Test
    void parse_emptyTemplate_returnsEmpty() {
        assertThat(ExpressionEvaluator.parse("")).isEmpty();
        assertThat(ExpressionEvaluator.parse(null)).isEmpty();
    }

    @Test
    void parse_staticOnly_singleLiteral() {
        List<ExpressionEvaluator.Segment> segs = ExpressionEvaluator.parse("hello world");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).expression()).isFalse();
        assertThat(segs.get(0).text()).isEqualTo("hello world");
    }

    @Test
    void parse_singleExprAlone_singleExpression() {
        List<ExpressionEvaluator.Segment> segs = ExpressionEvaluator.parse("{{ foo }}");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).expression()).isTrue();
        assertThat(segs.get(0).text()).isEqualTo("foo");
    }

    @Test
    void parse_unclosedOpen_treatedAsLiteral() throws Exception {
        // 닫히지 않은 {{ → 평가하지 않고 원문 보존
        List<ExpressionEvaluator.Segment> segs = ExpressionEvaluator.parse("a {{ unclosed");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).expression()).isFalse();
        assertThat(segs.get(0).text()).isEqualTo("a {{ unclosed");

        // evaluate도 동일 — 원문 그대로
        assertThat(evaluator.evaluate("a {{ unclosed", Map.of())).isEqualTo("a {{ unclosed");
    }

    @Test
    void parse_emptyExpr_treatedAsExpressionWithBlankBody() {
        // {{ }} 빈 표현식은 expression 세그먼트로 분류 — 평가 시 SyntaxError로 격하됨
        List<ExpressionEvaluator.Segment> segs = ExpressionEvaluator.parse("{{ }}");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).expression()).isTrue();
        assertThat(segs.get(0).text()).isEqualTo("");
    }
}
