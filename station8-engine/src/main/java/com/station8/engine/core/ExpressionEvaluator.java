package com.station8.engine.core;

import jakarta.annotation.PreDestroy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M16 (#247) — GraalVM JavaScript 표현식 평가기.
 *
 * <p>사용자 입력 안의 {@code {{ ... }}} 표현식을 JavaScript로 평가해 값을 반환한다.
 * RFC #255 결정에 따라 n8n과 동일한 {@code {{ $prev.json.id }}} 문법.</p>
 *
 * <h3>반환 규칙</h3>
 * <ul>
 *   <li>template이 {@code null} → {@code null}</li>
 *   <li>표현식 0개 (정적 텍스트만) → 입력 그대로 (불필요한 평가 스킵)</li>
 *   <li>단일 {@code {{ expr }}} 단독 (양옆 텍스트 0) → 평가 결과 raw value (타입 보존)</li>
 *   <li>{@code {{ expr }}} 여러 개 또는 정적 텍스트와 혼재 → String join</li>
 * </ul>
 *
 * <h3>Sandbox 정책</h3>
 * <p>RFC #255 §"GraalVM JavaScript ✅ 채택"의 deny-all 정책 그대로:</p>
 * <ul>
 *   <li>{@link HostAccess#NONE} — Java reflection / 임의 메서드 호출 차단</li>
 *   <li>{@link IOAccess#NONE} — 파일 시스템 / 네트워크 호스트 액세스 차단</li>
 *   <li>{@code allowCreateThread=false} — 스레드 생성 차단</li>
 *   <li>{@code allowNativeAccess=false} — JNI / Polyglot.import 차단</li>
 *   <li>{@code js.console=false} / {@code js.load=false} — console / load 차단</li>
 * </ul>
 *
 * <h3>Polyglot Context 라이프사이클</h3>
 * <p>{@link Engine}은 thread-safe + JIT 캐시 보유 → 인스턴스 필드로 1개 공유.
 * {@link Context}는 thread-unsafe + 평가 상태 보유 → 매 {@link #evaluate(String, Map)}
 * 호출마다 신규 생성 후 try-with-resources로 close. 평가 사이 binding 누수 방지가
 * 캐싱 이득보다 우선이라는 판단(#257). 측정은 #261에서.</p>
 *
 * <h3>Timeout</h3>
 * <p>현재는 timeout 가드 없음 — 무한 루프 표현식이 워커 스레드를 블록할 수 있음.
 * GraalVM Community에서 timeout은 {@link Context#interrupt}를 외부 스케줄러로 호출하는
 * 방식이라 별도 sub-issue에서 정책 결정. #261 측정 결과와 함께 다룬다.</p>
 *
 * <h3>향후 (RFC #255 fallback)</h3>
 * <p>이미지 크기 측정 결과(#261)가 나쁘면 본 클래스를 인터페이스로 추출하고
 * {@code station8-expression-js.jar}로 분리하는 fallback이 RFC에 명시됨.</p>
 */
@Component
public class ExpressionEvaluator implements AutoCloseable {

    private static final String LANGUAGE = "js";
    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";

    private final Engine engine;

    public ExpressionEvaluator() {
        this.engine = Engine.newBuilder(LANGUAGE)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /**
     * 템플릿 평가.
     *
     * @param template 사용자 입력 ({@code null} 허용 → {@code null} 반환)
     * @param bindings 표현식이 참조할 전역 변수 ({@code $prev}, {@code $ctx} 등). {@code null}이면 빈 바인딩.
     * @return 평가 결과 — 단일 표현식이면 raw value, 그 외엔 String
     * @throws ExpressionEvaluationException 파싱/평가 실패 시 (워커는 죽지 않으며,
     *         호출부가 액티비티 FAILED로 격하한다)
     */
    public Object evaluate(String template, Map<String, Object> bindings) throws ExpressionEvaluationException {
        if (template == null) return null;
        List<Segment> segments = parse(template);
        if (segments.isEmpty()) return template;

        boolean hasExpr = segments.stream().anyMatch(s -> s.expression);
        if (!hasExpr) return template;

        // 단일 표현식 단독 → raw value (타입 보존)
        if (segments.size() == 1 && segments.get(0).expression) {
            return evalOne(segments.get(0).text, bindings, template);
        }

        StringBuilder out = new StringBuilder(template.length());
        for (Segment seg : segments) {
            if (seg.expression) {
                Object v = evalOne(seg.text, bindings, template);
                out.append(v == null ? "" : v.toString());
            } else {
                out.append(seg.text);
            }
        }
        return out.toString();
    }

    private Object evalOne(String expr, Map<String, Object> bindings, String fullTemplate)
            throws ExpressionEvaluationException {
        try (Context ctx = Context.newBuilder(LANGUAGE)
                .engine(engine)
                .allowHostAccess(HostAccess.NONE)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                // js.console / js.load는 GraalVM JS의 experimental option — 의도적으로 활성화해
                // 비표준 global (console, load)을 sandbox 외부로 노출하지 않게 차단.
                .allowExperimentalOptions(true)
                .option("js.console", "false")
                .option("js.load", "false")
                .build()) {
            Value globalScope = ctx.getBindings(LANGUAGE);
            if (bindings != null) {
                for (Map.Entry<String, Object> e : bindings.entrySet()) {
                    globalScope.putMember(e.getKey(), e.getValue());
                }
            }
            Value result = ctx.eval(Source.create(LANGUAGE, expr));
            return toJavaValue(result);
        } catch (PolyglotException ex) {
            throw new ExpressionEvaluationException("표현식 평가 실패", expr, fullTemplate, ex);
        } catch (RuntimeException ex) {
            throw new ExpressionEvaluationException("표현식 평가 중 예외", expr, fullTemplate, ex);
        }
    }

    private static Object toJavaValue(Value v) {
        if (v == null || v.isNull()) return null;
        if (v.isString()) return v.asString();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) {
            if (v.fitsInLong()) return v.asLong();
            return v.asDouble();
        }
        if (v.isHostObject()) return v.asHostObject();
        // JS 배열 / Proxy array → Java List
        if (v.hasArrayElements()) {
            long n = v.getArraySize();
            List<Object> list = new ArrayList<>((int) n);
            for (long i = 0; i < n; i++) list.add(toJavaValue(v.getArrayElement(i)));
            return list;
        }
        // JS 객체 / Proxy object → Java Map
        if (v.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>(v.getMemberKeys().size());
            for (String key : v.getMemberKeys()) {
                map.put(key, toJavaValue(v.getMember(key)));
            }
            return map;
        }
        return v.toString();
    }

    /**
     * 템플릿을 정적 텍스트와 {@code {{ expr }}} 세그먼트로 분해.
     *
     * <p>닫히지 않은 {@code {{}}는 정적 텍스트로 취급 — 사용자 실수에 평가 실패로 깨지기보다
     * 원문 보존이 안전. 중첩은 지원하지 않음 (n8n과 동일).</p>
     */
    static List<Segment> parse(String template) {
        if (template == null || template.isEmpty()) return List.of();
        List<Segment> out = new ArrayList<>();
        int i = 0;
        int n = template.length();
        while (i < n) {
            int open = template.indexOf(OPEN, i);
            if (open < 0) {
                out.add(Segment.literal(template.substring(i)));
                break;
            }
            int close = template.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                out.add(Segment.literal(template.substring(i)));
                break;
            }
            if (open > i) out.add(Segment.literal(template.substring(i, open)));
            String expr = template.substring(open + OPEN.length(), close).trim();
            out.add(Segment.expression(expr));
            i = close + CLOSE.length();
        }
        return out;
    }

    record Segment(boolean expression, String text) {
        static Segment literal(String s) { return new Segment(false, s); }
        static Segment expression(String s) { return new Segment(true, s); }
    }

    @PreDestroy
    @Override
    public void close() {
        if (engine != null) {
            engine.close();
        }
    }

    /** 평가 도중 예외. 호출부가 액티비티 FAILED 사유로 사용. */
    public static class ExpressionEvaluationException extends Exception {
        private final String expression;
        private final String template;

        public ExpressionEvaluationException(String message, String expression, String template, Throwable cause) {
            super(message + " — expr='" + expression + "' template='" + truncate(template) + "': " + cause.getMessage(), cause);
            this.expression = expression;
            this.template = template;
        }

        public String expression() { return expression; }
        public String template() { return template; }

        private static String truncate(String s) {
            if (s == null) return "null";
            return s.length() > 200 ? s.substring(0, 200) + "..." : s;
        }
    }
}
