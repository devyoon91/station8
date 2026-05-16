package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M16 (#247) — {@link LineContext}의 도메인 데이터를 {@link ExpressionEvaluator} polyglot binding으로 변환.
 *
 * <h3>노출 변수</h3>
 * <ul>
 *   <li>{@code $prev} — {@code { json, binary }} — 직전 노드 출력 (n8n 호환 shape)</li>
 *   <li>{@code $ctx} — {@code { input, run, line, runtime }} — station8 고유 컨텍스트</li>
 *   <li>{@code $credentials} — placeholder (빈 객체). M17 (#248) 머지 후 vault에서 풀이</li>
 * </ul>
 *
 * <h3>Sandbox 안전성</h3>
 * Map / List는 {@link ProxyObject} / {@link ProxyArray}로 wrap — JS에선 키/인덱스 액세스만 가능하고
 * Java 메서드 ({@code toString}, {@code getClass} 등)는 호출 불가. {@link ExpressionEvaluator}의
 * {@code HostAccess.NONE} 정책과 함께 사용자 표현식이 Java 객체 그래프로 escape하는 경로를 차단한다.
 *
 * <h3>String 입력의 JSON 파싱</h3>
 * {@link LineContext#input()} / {@link LineContext#previousOutput()}이 String이고 {@code {} 또는 {@code []}로
 * 시작하면 JSON 파싱을 시도해 객체로 노출. 그 외에는 raw string 그대로 (사용자가 {@code $ctx.input == 'OK'}
 * 같은 비교를 쓸 수 있게).
 */
@Component
public class LineContextBindings {

    public static final String PREV = "$prev";
    public static final String CTX = "$ctx";
    public static final String CREDENTIALS = "$credentials";

    private final JsonUtil jsonUtil;
    private final CredentialResolver credentialResolver;

    /** Spring auto-wired constructor (#272). */
    @org.springframework.beans.factory.annotation.Autowired
    public LineContextBindings(JsonUtil jsonUtil, CredentialResolver credentialResolver) {
        this.jsonUtil = jsonUtil;
        this.credentialResolver = credentialResolver;
    }

    /**
     * 후방 호환 — credential resolver 없이 생성. $credentials는 빈 ProxyObject 반환.
     * 새 코드는 위 2-arg 생성자 사용 권장. 본 생성자는 vault 미사용 단위 테스트 등 한정.
     */
    public LineContextBindings(JsonUtil jsonUtil) {
        this(jsonUtil, NoCredentialsResolver.INSTANCE);
    }

    /** 빈 vault — getMember는 항상 null, hasMember는 항상 false. */
    static final class NoCredentialsResolver extends CredentialResolver {
        static final NoCredentialsResolver INSTANCE = new NoCredentialsResolver();
        private NoCredentialsResolver() {
            super(null, null, null);
        }
        @Override
        public Object topLevelBinding() {
            return ProxyObject.fromMap(Map.of());
        }
    }

    /**
     * 표현식 평가에 주입할 binding 맵을 생성한다.
     *
     * @param context 활동 실행 컨텍스트
     * @return {@link ExpressionEvaluator#evaluate(String, Map)}에 그대로 넘길 수 있는 binding 맵
     */
    public Map<String, Object> from(LineContext context) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put(PREV, prevBinding(context));
        bindings.put(CTX, ctxBinding(context));
        bindings.put(CREDENTIALS, credentialsBinding());
        return bindings;
    }

    private Object prevBinding(LineContext context) {
        Object prevOutput = context.previousOutput().orElse(null);
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("json", toJsExposable(prevOutput));
        // M22 item-streaming/binary 영역은 별도 마일스톤 — placeholder.
        shape.put("binary", ProxyObject.fromMap(Map.of()));
        return ProxyObject.fromMap(shape);
    }

    private Object ctxBinding(LineContext context) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("input", toJsExposable(context.input()));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", context.instanceId());
        run.put("attempt", (long) context.attempt());
        shape.put("run", ProxyObject.fromMap(run));

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", context.workflowName());
        line.put("activity", context.currentActivityName());
        shape.put("line", ProxyObject.fromMap(line));

        // #134 runtime params — JS에서 $ctx.runtime.<key>로 접근.
        shape.put("runtime", ProxyObject.fromMap(toStringObjectMap(context.runtimeParams())));

        return ProxyObject.fromMap(shape);
    }

    private Object credentialsBinding() {
        // M17 (#272) — vault에서 lazy 해소. 표현식이 $credentials.foo.value를 액세스하면
        // 그 시점에 findByName + decrypt 1회. 미사용 시 DB 쿼리/decrypt 0.
        return credentialResolver.topLevelBinding();
    }

    /**
     * Java 객체를 JS sandbox에서 안전하게 액세스 가능한 형태로 변환.
     *
     * <ul>
     *   <li>String + JSON shape ({{ } 또는 [) → 파싱 후 재귀 변환, 실패 시 raw</li>
     *   <li>Map → {@link ProxyObject} (값 재귀 변환)</li>
     *   <li>List → {@link ProxyArray} (값 재귀 변환)</li>
     *   <li>Number / Boolean / null → as-is</li>
     *   <li>그 외 Java 객체 → {@code String.valueOf} (reflection escape 차단)</li>
     * </ul>
     */
    Object toJsExposable(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    Object parsed = jsonUtil.fromJson(s, Object.class);
                    return toJsExposable(parsed);
                } catch (Exception ignored) {
                    return s;
                }
            }
            return s;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), toJsExposable(e.getValue()));
            }
            return ProxyObject.fromMap(out);
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(toJsExposable(x));
            return ProxyArray.fromList(out);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> toStringObjectMap(Map<String, String> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) out.putAll(in);
        return out;
    }
}
