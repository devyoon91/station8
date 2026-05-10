package com.station8.engine.core;

import com.station8.engine.annotation.BoundDataSource;
import com.station8.engine.datasource.DataSourceInfo;
import com.station8.engine.datasource.DataSourceRegistry;
import com.station8.engine.dialect.DbDialect;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ActivityArgumentResolver}의 파라미터 바인딩 동작 검증 (#108).
 *
 * <p>지원 시그니처:</p>
 * <ul>
 *   <li>{@code ()} — 빈 인자 배열</li>
 *   <li>{@code (String)} — inputData 전달</li>
 *   <li>{@code (String, DataSourceRegistry)} — 둘 다 주입</li>
 *   <li>{@code (DataSourceRegistry)} — 레지스트리만</li>
 *   <li>그 외 — {@link IllegalStateException}</li>
 * </ul>
 */
class ActivityArgumentResolverTest {

    private final DataSourceRegistry stubRegistry = new StubRegistry();
    private final ActivityArgumentResolver resolver = new ActivityArgumentResolver(stubRegistry);

    @Test
    void resolve_noArgMethod_returnsEmptyArray() throws Exception {
        Method m = Probe.class.getMethod("noArg");
        assertThat(resolver.resolve(m, "ignored")).isEmpty();
    }

    @Test
    void resolve_singleStringMethod_passesInputData() throws Exception {
        Method m = Probe.class.getMethod("singleString", String.class);
        Object[] args = resolver.resolve(m, "payload-1");
        assertThat(args).containsExactly("payload-1");
    }

    @Test
    void resolve_stringPlusRegistry_bindsBoth() throws Exception {
        Method m = Probe.class.getMethod("withRegistry", String.class, DataSourceRegistry.class);
        Object[] args = resolver.resolve(m, "payload-2");
        assertThat(args).hasSize(2);
        assertThat(args[0]).isEqualTo("payload-2");
        assertThat(args[1]).isSameAs(stubRegistry);
    }

    @Test
    void resolve_registryOnly_bindsRegistry() throws Exception {
        Method m = Probe.class.getMethod("registryOnly", DataSourceRegistry.class);
        Object[] args = resolver.resolve(m, "ignored");
        assertThat(args).hasSize(1);
        assertThat(args[0]).isSameAs(stubRegistry);
    }

    @Test
    void resolve_unsupportedType_throwsIllegalState() throws Exception {
        Method m = Probe.class.getMethod("unsupported", Integer.class);
        assertThatThrownBy(() -> resolver.resolve(m, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported activity parameter type")
                .hasMessageContaining("Integer");
    }

    @Test
    void resolve_boundDataSource_picksFromStationBindings() throws Exception {
        // Stub registry: jdbc("oracle-prod") → 특정 인스턴스
        JdbcTemplate sourceJdbc = new JdbcTemplate();
        JdbcTemplate targetJdbc = new JdbcTemplate();
        BindingAwareStubRegistry boundRegistry = new BindingAwareStubRegistry(Map.of(
                "oracle-prod", sourceJdbc,
                "mart", targetJdbc
        ));
        ActivityArgumentResolver resolver = new ActivityArgumentResolver(boundRegistry);

        Method m = Probe.class.getMethod("withBoundJdbc", String.class, JdbcTemplate.class, JdbcTemplate.class);
        Map<String, String> bindings = new HashMap<>();
        bindings.put("source", "oracle-prod");
        bindings.put("target", "mart");
        Object[] args = resolver.resolve(m, new ActivityArgumentResolver.Context("payload", bindings));

        assertThat(args).hasSize(3);
        assertThat(args[0]).isEqualTo("payload");
        assertThat(args[1]).isSameAs(sourceJdbc);
        assertThat(args[2]).isSameAs(targetJdbc);
    }

    @Test
    void resolve_boundDataSource_missingRole_fallsBackToPrimary() throws Exception {
        JdbcTemplate primaryJdbc = new JdbcTemplate();
        BindingAwareStubRegistry boundRegistry = new BindingAwareStubRegistry(Map.of(
                "primary", primaryJdbc
        ));
        ActivityArgumentResolver resolver = new ActivityArgumentResolver(boundRegistry);

        Method m = Probe.class.getMethod("singleBoundJdbc", String.class, JdbcTemplate.class);
        // bindings에 "source" 키가 없음 → primary fallback
        Object[] args = resolver.resolve(m, new ActivityArgumentResolver.Context("x", Map.of()));
        assertThat(args[1]).isSameAs(primaryJdbc);
    }

    @Test
    void resolve_boundDataSource_unknownName_fallsBackToPrimary() throws Exception {
        JdbcTemplate primaryJdbc = new JdbcTemplate();
        BindingAwareStubRegistry boundRegistry = new BindingAwareStubRegistry(Map.of(
                "primary", primaryJdbc
                // "ghost"는 등록 안 됨
        ));
        ActivityArgumentResolver resolver = new ActivityArgumentResolver(boundRegistry);

        Method m = Probe.class.getMethod("singleBoundJdbc", String.class, JdbcTemplate.class);
        Object[] args = resolver.resolve(m, new ActivityArgumentResolver.Context("x",
                Map.of("source", "ghost")));
        assertThat(args[1]).isSameAs(primaryJdbc);
    }

    @Test
    void resolve_boundDataSource_onNonJdbcParameter_throws() throws Exception {
        Method m = Probe.class.getMethod("invalidBoundOnString", String.class);
        assertThatThrownBy(() -> resolver.resolve(m, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@BoundDataSource is only supported on JdbcTemplate");
    }

    @Test
    void resolve_lineContextParameter_injectsContext() throws Exception {
        // #134 D7 — LineContext 파라미터로 받으면 ctx.lineContext()를 그대로 주입
        Method m = Probe.class.getMethod("withContext", String.class, LineContext.class);
        DefaultLineContext ctx = new DefaultLineContext("inst-1", "wf", "act", 1, "input", null, null);
        ctx.setRuntimeParams(Map.of("k", "v"));
        Object[] args = resolver.resolve(m,
                new ActivityArgumentResolver.Context("payload", Map.of(), ctx));

        assertThat(args).hasSize(2);
        assertThat(args[0]).isEqualTo("payload");
        assertThat(args[1]).isSameAs(ctx);
        assertThat(((LineContext) args[1]).runtimeParams()).containsEntry("k", "v");
    }

    public static class Probe {
        public void noArg() {}
        public String singleString(String input) { return input; }
        public String withRegistry(String input, DataSourceRegistry ds) { return input; }
        public String registryOnly(DataSourceRegistry ds) { return "ok"; }
        public String unsupported(Integer x) { return ""; }
        public String withBoundJdbc(String input,
                                    @BoundDataSource("source") JdbcTemplate src,
                                    @BoundDataSource("target") JdbcTemplate dst) { return input; }
        public String singleBoundJdbc(String input, @BoundDataSource("source") JdbcTemplate src) { return input; }
        public String invalidBoundOnString(@BoundDataSource("nope") String s) { return s; }
        public String withContext(String input, LineContext ctx) { return input; }
    }

    /** jdbc(name) 호출만 동작하는 stub — bound binding 테스트용. */
    private static class BindingAwareStubRegistry implements DataSourceRegistry {
        private final Map<String, JdbcTemplate> templates;
        BindingAwareStubRegistry(Map<String, JdbcTemplate> templates) { this.templates = templates; }
        @Override public Set<String> names() { return templates.keySet(); }
        @Override public DataSource dataSource(String name) { throw new UnsupportedOperationException(); }
        @Override public JdbcTemplate jdbc(String name) {
            JdbcTemplate t = templates.get(name);
            if (t == null) throw new IllegalArgumentException("Unknown DataSource name: " + name);
            return t;
        }
        @Override public DbDialect dialect(String name) { throw new UnsupportedOperationException(); }
        @Override public TestResult testConnection(String name) { throw new UnsupportedOperationException(); }
        @Override public List<DataSourceInfo> snapshot() { return List.of(); }
        @Override public DataSourceInfo info(String name) { throw new UnsupportedOperationException(); }
        @Override public Source sourceOf(String name) { return templates.containsKey(name) ? Source.STATIC : Source.NONE; }
        @Override public TestResult register(DynamicSpec spec) { throw new UnsupportedOperationException(); }
        @Override public TestResult swap(DynamicSpec spec) { throw new UnsupportedOperationException(); }
        @Override public void unregister(String name) { throw new UnsupportedOperationException(); }
    }

    /** 테스트용 stub — 실제 풀 없이 ID 비교만 가능하면 충분. */
    private static class StubRegistry implements DataSourceRegistry {
        @Override public Set<String> names() { return Set.of(); }
        @Override public DataSource dataSource(String name) { throw new UnsupportedOperationException(); }
        @Override public JdbcTemplate jdbc(String name) { throw new UnsupportedOperationException(); }
        @Override public DbDialect dialect(String name) { throw new UnsupportedOperationException(); }
        @Override public TestResult testConnection(String name) { return new TestResult(name, true, 0L, null); }
        @Override public List<DataSourceInfo> snapshot() { return List.of(); }
        @Override public DataSourceInfo info(String name) { throw new UnsupportedOperationException(); }
        @Override public Source sourceOf(String name) { return Source.NONE; }
        @Override public TestResult register(DynamicSpec spec) { throw new UnsupportedOperationException(); }
        @Override public TestResult swap(DynamicSpec spec) { throw new UnsupportedOperationException(); }
        @Override public void unregister(String name) { throw new UnsupportedOperationException(); }
    }
}
