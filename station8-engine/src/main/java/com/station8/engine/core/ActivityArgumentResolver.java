package com.station8.engine.core;

import com.station8.engine.annotation.BoundDataSource;
import com.station8.engine.datasource.DataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * 액티비티 메서드 호출 시 파라미터를 바인딩하는 책임 객체.
 *
 * <p>지원 파라미터 타입:</p>
 * <ul>
 *   <li>{@code String} — 첫 번째 등장 시 ``inputData``를 그대로 전달</li>
 *   <li>{@link DataSourceRegistry} — 멀티 DS 액세스 직접 호출 (#108 D3, #113에서 deprecated 권장)</li>
 *   <li>{@code @BoundDataSource("role") JdbcTemplate} — station 바인딩(#113) 기반 자동 주입</li>
 *   <li>{@link LineContext} — 인스턴스 메타 + runtime params(#134 D7) 접근</li>
 * </ul>
 *
 * <p>지원하지 않는 타입이 선언되면 {@link IllegalStateException}을 던져 등록 단계가 아닌
 * 호출 단계에서 명시적으로 실패시킨다.</p>
 *
 * <p>{@link Context}는 호출 시점의 station 메타(특히 {@code datasourceBindings})를 함께
 * 전달하기 위한 컨테이너 — LineWorker가 station 조회 결과로 채워서 넘긴다.</p>
 */
@Component
public class ActivityArgumentResolver {

    private static final Logger log = LoggerFactory.getLogger(ActivityArgumentResolver.class);

    private final DataSourceRegistry dataSourceRegistry;

    public ActivityArgumentResolver(DataSourceRegistry dataSourceRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * @param method 호출 대상 액티비티 메서드
     * @param ctx    호출 컨텍스트 (input + station bindings)
     * @return method.invoke()에 그대로 넘길 인자 배열
     * @throws IllegalStateException 지원하지 않는 파라미터 타입을 만난 경우
     */
    public Object[] resolve(Method method, Context ctx) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length == 0) {
            return new Object[0];
        }
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        Object[] args = new Object[types.length];
        boolean inputBound = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            BoundDataSource bound = findBoundDataSource(paramAnnotations[i]);

            if (bound != null) {
                if (!JdbcTemplate.class.isAssignableFrom(t)) {
                    throw new IllegalStateException(
                            "@BoundDataSource is only supported on JdbcTemplate parameters — got "
                                    + t.getName() + " at index " + i + " on "
                                    + method.getDeclaringClass().getSimpleName()
                                    + "#" + method.getName());
                }
                args[i] = resolveBoundDataSource(method, i, bound.value(), ctx);
            } else if (t == String.class && !inputBound) {
                args[i] = ctx.inputData();
                inputBound = true;
            } else if (t.isAssignableFrom(DataSourceRegistry.class)
                    || DataSourceRegistry.class.isAssignableFrom(t)) {
                args[i] = dataSourceRegistry;
            } else if (LineContext.class.isAssignableFrom(t)) {
                // #134 D7 — LineContext 주입 (runtime params 접근용)
                args[i] = ctx.lineContext();
            } else {
                throw new IllegalStateException(
                        "Unsupported activity parameter type: " + t.getName()
                                + " at index " + i + " on "
                                + method.getDeclaringClass().getSimpleName()
                                + "#" + method.getName()
                                + " — supported: String (input), DataSourceRegistry, "
                                + "@BoundDataSource JdbcTemplate, LineContext");
            }
        }
        return args;
    }

    /**
     * Backward-compat shim — String input만 있는 호출은 빈 bindings로 처리.
     * 새 코드는 {@link #resolve(Method, Context)} 사용 권장.
     */
    public Object[] resolve(Method method, String inputData) {
        return resolve(method, new Context(inputData, Collections.emptyMap(), null));
    }

    private JdbcTemplate resolveBoundDataSource(Method method, int paramIndex,
                                                String role, Context ctx) {
        String dsName = ctx.datasourceBindings().get(role);
        if (dsName == null || dsName.isBlank()) {
            log.warn("Station 바인딩 누락 — role='{}' on {}#{} (param {}) → primary fallback",
                    role, method.getDeclaringClass().getSimpleName(),
                    method.getName(), paramIndex);
            return dataSourceRegistry.jdbc("primary");
        }
        try {
            return dataSourceRegistry.jdbc(dsName);
        } catch (IllegalArgumentException ex) {
            log.warn("등록되지 않은 DataSource — name='{}' (role='{}' on {}#{}) → primary fallback",
                    dsName, role, method.getDeclaringClass().getSimpleName(), method.getName());
            return dataSourceRegistry.jdbc("primary");
        }
    }

    private static BoundDataSource findBoundDataSource(Annotation[] anns) {
        for (Annotation a : anns) {
            if (a instanceof BoundDataSource b) return b;
        }
        return null;
    }

    /**
     * 액티비티 호출 컨텍스트.
     *
     * @param inputData          액티비티 입력 페이로드
     * @param datasourceBindings station의 role→DS name 매핑 (null이면 빈 맵으로 처리)
     * @param lineContext        활동에 주입할 {@link LineContext} (null이면 활동이 LineContext를 인자로
     *                           받았을 때 null이 주입됨 — 워커가 인스턴스 메타로부터 빌드해 넘긴다)
     */
    public record Context(String inputData,
                          Map<String, String> datasourceBindings,
                          LineContext lineContext) {
        public Context {
            if (datasourceBindings == null) datasourceBindings = Collections.emptyMap();
        }

        /** 후방 호환 — 2-arg 생성자 (lineContext null). */
        public Context(String inputData, Map<String, String> datasourceBindings) {
            this(inputData, datasourceBindings, null);
        }
    }
}
