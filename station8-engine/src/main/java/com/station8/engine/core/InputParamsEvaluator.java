package com.station8.engine.core;

import com.station8.engine.exception.LineEngineException;
import com.station8.engine.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M16 (#247) — 활동 {@code inputData}의 {@code {{ ... }}} 표현식을 풀어 평가된 값으로 치환.
 *
 * <p>{@link ActivityProcessor}가 {@link ActivityArgumentResolver}로 인자를 바인딩하기 직전에
 * 한 번 호출. 사용자가 노드 입력에 쓴 표현식이 활동 메서드에 도달하기 전에 평가된다.</p>
 *
 * <h3>두 가지 평가 모드</h3>
 *
 * <ol>
 *   <li><b>JSON 모드</b> — inputData가 {@code {} 또는 {@code []}로 시작 + JSON 파싱 성공:
 *     <ul>
 *       <li>모든 string 리프 노드를 재귀 순회</li>
 *       <li>{@code {{ ... }}} 포함 시 평가기 호출 → raw value (Number/Boolean/Object/Array)로 치환</li>
 *       <li>치환된 트리를 다시 JSON 직렬화해 반환 — 활동 메서드는 평가 결과를 JSON으로 받는다</li>
 *     </ul>
 *   </li>
 *   <li><b>String 모드</b> — JSON이 아닌 일반 문자열:
 *     <ul>
 *       <li>전체 문자열을 평가기에 그대로 전달</li>
 *       <li>{@code "Hello {{ name }}!"} → {@code "Hello world!"}</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>스킵 경로</h3>
 * inputData에 {@code "{{"}가 없으면 평가/JSON 파싱 모두 스킵 → 정적 노드 입력의 오버헤드 0.
 *
 * <h3>평가 실패 처리</h3>
 * {@link com.station8.engine.core.ExpressionEvaluator.ExpressionEvaluationException}을 그대로 위로 전파.
 * {@link ActivityProcessor}가 catch해서 활동 FAILED로 격하한다 (워커 죽지 않음).
 *
 * <h3>옵트아웃 / 이스케이프</h3>
 * 본 버전은 옵트아웃 메커니즘 없음 — 모든 활동의 inputData가 평가 대상. {@code "{{"} 자체를
 * 리터럴로 쓰고 싶은 시나리오는 후속 sub-issue에서 별도 처리 (현재 대다수 사용자에겐 표현식
 * 활성이 기본 기대치).
 */
@Component
public class InputParamsEvaluator {

    private static final String EXPR_OPEN = "{{";

    private final ExpressionEvaluator evaluator;
    private final LineContextBindings bindingsFactory;
    private final JsonUtil jsonUtil;

    public InputParamsEvaluator(ExpressionEvaluator evaluator,
                                LineContextBindings bindingsFactory,
                                JsonUtil jsonUtil) {
        this.evaluator = evaluator;
        this.bindingsFactory = bindingsFactory;
        this.jsonUtil = jsonUtil;
    }

    /**
     * inputData 안의 표현식을 평가해 치환한다.
     *
     * @param inputData 활동 입력 페이로드 ({@code null} / 표현식 없음 → 그대로 반환)
     * @param context   평가에 노출할 LineContext ($prev, $ctx, $credentials)
     * @return 평가 결과 — JSON 모드면 재직렬화된 JSON, String 모드면 보간된 String
     * @throws ExpressionEvaluator.ExpressionEvaluationException 평가 실패 시 (호출부가 활동 FAILED로 격하)
     */
    public String evaluate(String inputData, LineContext context)
            throws ExpressionEvaluator.ExpressionEvaluationException {
        // 빠른 스킵 — 표현식 없으면 평가 자체를 안 함 (정적 입력 회귀 가드)
        if (inputData == null || !inputData.contains(EXPR_OPEN)) return inputData;

        Map<String, Object> bindings = bindingsFactory.from(context);

        String trimmed = inputData.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                Object parsed = jsonUtil.fromJson(inputData, Object.class);
                Object evaluated = walk(parsed, bindings);
                return jsonUtil.toJson(evaluated);
            } catch (LineEngineException ignored) {
                // JSON 파싱 실패 → string 모드로 fall-through
            }
        }

        Object result = evaluator.evaluate(inputData, bindings);
        return result == null ? null : result.toString();
    }

    /**
     * JSON tree를 재귀 순회하며 string 리프의 표현식을 평가/치환.
     *
     * <p>Map / List는 새 컨테이너를 만들어 반환 (원본 비파괴). String 외 primitive는 그대로.</p>
     */
    private Object walk(Object node, Map<String, Object> bindings)
            throws ExpressionEvaluator.ExpressionEvaluationException {
        if (node == null) return null;
        if (node instanceof String s) {
            if (!s.contains(EXPR_OPEN)) return s;
            return evaluator.evaluate(s, bindings);
        }
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), walk(e.getValue(), bindings));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(walk(x, bindings));
            return out;
        }
        return node;
    }
}
