package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * 엣지 조건식(SpEL) 평가기 (#152).
 *
 * <p>활동 결과 JSON을 {@code #result} 변수로 받아 SpEL 표현식을 평가한다.
 * {@link SimpleEvaluationContext#forReadOnlyDataBinding()}로 reflection / 임의 코드 호출
 * 차단 (보안). instance 메서드(예: {@code String.length()})는 허용해 가벼운 표현식 친화.</p>
 *
 * <h3>표현식 예시</h3>
 * <pre>{@code
 *   #result['success'] == true
 *   #result['count'] > 10
 *   #result['status'] == 'OK' and #result['errors'] == 0
 *   #result.size() > 0
 * }</pre>
 *
 * <h3>평가 결과</h3>
 * <ul>
 *   <li>{@code true} → 조건 만족 (호출자가 후행 노드 활성화)</li>
 *   <li>{@code false} → 조건 불만족 (호출자가 그 엣지 비활성)</li>
 *   <li>{@link ConditionEvaluationException} → 파싱/평가 예외 (호출자가 인스턴스 FAILED 처리)</li>
 * </ul>
 *
 * <h3>활동 결과 binding 규칙</h3>
 * <ul>
 *   <li>JSON object → {@code Map}으로 파싱, {@code #result['key']}로 접근</li>
 *   <li>JSON array → {@code List}로 파싱, {@code #result[0]}으로 접근</li>
 *   <li>JSON 아닌 raw string → {@code String}으로 노출, {@code #result == 'OK'} 같은 비교 가능</li>
 *   <li>null/blank → 빈 {@code Map} (조건은 일반적으로 false)</li>
 * </ul>
 */
@Component
public class EdgeConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EdgeConditionEvaluator.class);

    private final ExpressionParser parser = new SpelExpressionParser();
    private final JsonUtil jsonUtil;

    public EdgeConditionEvaluator(JsonUtil jsonUtil) {
        this.jsonUtil = jsonUtil;
    }

    /**
     * 정의 저장 시 SpEL 문법 검증 — 컴파일만 시도. 평가는 안 함.
     *
     * @throws IllegalArgumentException 파싱 실패 시 (호출자가 LineEngineException으로 매핑)
     */
    public void validateCompile(String expr) {
        if (expr == null || expr.isBlank()) return;
        try {
            parser.parseExpression(expr);
        } catch (SpelParseException ex) {
            throw new IllegalArgumentException("SpEL 파싱 실패: " + ex.getMessage(), ex);
        }
    }

    /**
     * 조건 평가. {@code conditionExpr}이 null/blank이면 항상 {@code true} (조건 없음 = 항상 진행).
     *
     * @param conditionExpr SpEL 표현식 (선택)
     * @param resultJson    활동 결과 JSON 문자열
     * @return 조건 만족 여부
     * @throws ConditionEvaluationException 파싱/평가 도중 예외 발생 시
     */
    public boolean evaluate(String conditionExpr, String resultJson) throws ConditionEvaluationException {
        if (conditionExpr == null || conditionExpr.isBlank()) return true;
        try {
            Object result = parseResult(resultJson);
            SimpleEvaluationContext ctx = SimpleEvaluationContext
                    .forReadOnlyDataBinding()
                    .withInstanceMethods()
                    .build();
            ctx.setVariable("result", result);
            Expression exp = parser.parseExpression(conditionExpr);
            Boolean value = exp.getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(value);
        } catch (SpelParseException ex) {
            throw new ConditionEvaluationException("SpEL 파싱 실패", conditionExpr, ex);
        } catch (SpelEvaluationException ex) {
            throw new ConditionEvaluationException("SpEL 평가 실패", conditionExpr, ex);
        } catch (Exception ex) {
            throw new ConditionEvaluationException("조건 평가 중 예외", conditionExpr, ex);
        }
    }

    /**
     * 활동 결과 JSON을 SpEL이 사용할 수 있는 객체로 파싱한다. JSON이 아니면 raw string 그대로.
     */
    private Object parseResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = jsonUtil.fromJson(resultJson, Object.class);
            return o == null ? new LinkedHashMap<>() : o;
        } catch (Exception ex) {
            // JSON 파싱 실패 — raw string으로 노출 (e.g. "OK" 같은 단순 값)
            log.debug("Result not JSON-parseable, exposing as raw string: {}", ex.getMessage());
            return resultJson;
        }
    }

    /** 평가 도중 예외. 인스턴스 FAILED 사유로 사용. */
    public static class ConditionEvaluationException extends Exception {
        private final String expression;

        public ConditionEvaluationException(String message, String expression, Throwable cause) {
            super(message + " — expr='" + expression + "': " + cause.getMessage(), cause);
            this.expression = expression;
        }

        public String expression() { return expression; }
    }
}
