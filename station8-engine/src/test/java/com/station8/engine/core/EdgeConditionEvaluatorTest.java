package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #152 — {@link EdgeConditionEvaluator} 단위 테스트.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>blank/null expr → 항상 true (조건 없음)</li>
 *   <li>JSON object/array/raw string 결과 binding</li>
 *   <li>SpEL 파싱 실패 / 평가 실패 / 보안 (reflection 차단)</li>
 *   <li>validateCompile 동작 — 저장 시점 검증</li>
 * </ul>
 */
class EdgeConditionEvaluatorTest {

    private final JsonUtil jsonUtil = new JsonUtil();
    private final EdgeConditionEvaluator evaluator = new EdgeConditionEvaluator(jsonUtil);

    // ---- evaluate: blank / null ----

    @Test
    void evaluate_nullExpr_returnsTrueAlways() throws Exception {
        assertThat(evaluator.evaluate(null, "{\"x\":1}")).isTrue();
        assertThat(evaluator.evaluate(null, null)).isTrue();
    }

    @Test
    void evaluate_blankExpr_returnsTrueAlways() throws Exception {
        assertThat(evaluator.evaluate("", "{}")).isTrue();
        assertThat(evaluator.evaluate("   ", "{}")).isTrue();
    }

    // ---- evaluate: JSON object binding ----

    @Test
    void evaluate_resultObjectBoolean_matched() throws Exception {
        assertThat(evaluator.evaluate("#result['success'] == true", "{\"success\":true}")).isTrue();
        assertThat(evaluator.evaluate("#result['success'] == true", "{\"success\":false}")).isFalse();
    }

    @Test
    void evaluate_resultObjectNumeric_comparison() throws Exception {
        assertThat(evaluator.evaluate("#result['count'] > 10", "{\"count\":42}")).isTrue();
        assertThat(evaluator.evaluate("#result['count'] > 10", "{\"count\":5}")).isFalse();
    }

    @Test
    void evaluate_resultObjectString_equality() throws Exception {
        assertThat(evaluator.evaluate("#result['status'] == 'OK'", "{\"status\":\"OK\"}")).isTrue();
        assertThat(evaluator.evaluate("#result['status'] == 'OK'", "{\"status\":\"FAIL\"}")).isFalse();
    }

    @Test
    void evaluate_compoundLogicalExpr() throws Exception {
        String expr = "#result['status'] == 'OK' and #result['errors'] == 0";
        assertThat(evaluator.evaluate(expr, "{\"status\":\"OK\",\"errors\":0}")).isTrue();
        assertThat(evaluator.evaluate(expr, "{\"status\":\"OK\",\"errors\":3}")).isFalse();
        assertThat(evaluator.evaluate(expr, "{\"status\":\"FAIL\",\"errors\":0}")).isFalse();
    }

    @Test
    void evaluate_missingKey_returnsFalseGracefully() throws Exception {
        // 누락된 키는 null → null == 'OK' false. SpEL은 null 비교를 false로 평가.
        assertThat(evaluator.evaluate("#result['absent'] == 'OK'", "{}")).isFalse();
    }

    // ---- evaluate: array / raw string ----

    @Test
    void evaluate_arrayResult_indexAccess() throws Exception {
        assertThat(evaluator.evaluate("#result[0] == 'first'", "[\"first\",\"second\"]")).isTrue();
        assertThat(evaluator.evaluate("#result.size() > 1", "[1,2,3]")).isTrue();
    }

    @Test
    void evaluate_rawStringResult_directComparison() throws Exception {
        // JSON 파싱 실패 — 결과는 raw string으로 노출
        assertThat(evaluator.evaluate("#result == 'OK'", "OK")).isTrue();
        assertThat(evaluator.evaluate("#result == 'OK'", "FAIL")).isFalse();
    }

    @Test
    void evaluate_nullResult_emptyMap() throws Exception {
        // null 결과 → 빈 맵. 어떤 키 액세스도 false 비교.
        assertThat(evaluator.evaluate("#result['x'] == 'y'", null)).isFalse();
    }

    // ---- evaluate: 예외 ----

    @Test
    void evaluate_invalidSpelSyntax_throws() {
        assertThatThrownBy(() -> evaluator.evaluate("#result['x' == 1", "{}"))
                .isInstanceOf(EdgeConditionEvaluator.ConditionEvaluationException.class)
                .hasMessageContaining("SpEL 파싱 실패");
    }

    @Test
    void evaluate_typeMismatchInExpr_throws() {
        // 숫자 비교 강제 — 문자열 'abc' > 5 는 SpEL 평가 예외
        assertThatThrownBy(() -> evaluator.evaluate("#result['x'] > 5", "{\"x\":\"abc\"}"))
                .isInstanceOf(EdgeConditionEvaluator.ConditionEvaluationException.class);
    }

    // ---- 보안 (SimpleEvaluationContext 사용 검증) ----

    @Test
    void evaluate_reflectionAttack_blocked() {
        // SimpleEvaluationContext.forReadOnlyDataBinding()은 T() 연산자로 임의 클래스 접근 차단
        // 다음 표현식이 평가되면 보안 결함 — 차단되어야 함
        String malicious = "T(java.lang.Runtime).getRuntime().exec('id').toString()";
        assertThatThrownBy(() -> evaluator.evaluate(malicious, "{}"))
                .isInstanceOf(EdgeConditionEvaluator.ConditionEvaluationException.class);
    }

    // ---- validateCompile ----

    @Test
    void validateCompile_validExpr_doesNotThrow() {
        evaluator.validateCompile("#result['success'] == true");
        evaluator.validateCompile("#result['count'] > 10 and #result['status'] == 'OK'");
        evaluator.validateCompile("#result.size() > 0");
    }

    @Test
    void validateCompile_invalidExpr_throws() {
        assertThatThrownBy(() -> evaluator.validateCompile("#result[ == "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SpEL 파싱 실패");
    }

    @Test
    void validateCompile_blankOrNull_noOp() {
        // blank/null은 검증 통과 — 조건 없음 의미
        evaluator.validateCompile(null);
        evaluator.validateCompile("");
        evaluator.validateCompile("   ");
    }
}
