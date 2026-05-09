package com.station8.engine.datasource;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.dialect.MariaDbDialect;
import com.station8.engine.dialect.OracleDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultDataSourceRegistry}의 동작 검증 (#108).
 *
 * <p>H2 in-memory DB를 primary + secondary로 각각 띄워 등록 / 조회 / Test ping / 닫기를 확인.</p>
 */
class DefaultDataSourceRegistryTest {

    private HikariDataSource primaryDs;
    private DefaultDataSourceRegistry registry;

    @AfterEach
    void cleanup() {
        if (registry != null) registry.close();
        if (primaryDs != null) primaryDs.close();
    }

    @Test
    void registersPrimaryAndSecondary_andQueriesBoth() {
        primaryDs = h2DataSource("primary-test");
        Station8DataSourcesProperties props = props(Map.of(
                "external-h2", entry("jdbc:h2:mem:secondary-test;DB_CLOSE_DELAY=-1", null)
        ));

        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props,
                "jdbc:h2:mem:primary-test", "sa");

        assertThat(registry.names()).containsExactlyInAnyOrder("primary", "external-h2");

        JdbcTemplate primary = registry.jdbc("primary");
        JdbcTemplate secondary = registry.jdbc("external-h2");
        assertThat(primary.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(secondary.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void testConnection_returnsSuccessForReachableDs() {
        primaryDs = h2DataSource("primary-ping");
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props(Map.of()),
                "jdbc:h2:mem:primary-ping", "sa");

        DataSourceRegistry.TestResult r = registry.testConnection("primary");
        assertThat(r.success()).isTrue();
        assertThat(r.errorMsg()).isNull();
        assertThat(r.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testConnection_unknownName_returnsFailure() {
        primaryDs = h2DataSource("primary-unknown");
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props(Map.of()),
                "jdbc:h2:mem:primary-unknown", "sa");

        DataSourceRegistry.TestResult r = registry.testConnection("does-not-exist");
        assertThat(r.success()).isFalse();
        assertThat(r.errorMsg()).contains("Unknown DataSource");
    }

    @Test
    void unknownName_dataSource_throws() {
        primaryDs = h2DataSource("primary-unknown2");
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props(Map.of()),
                "jdbc:h2:mem:primary-unknown2", "sa");

        assertThatThrownBy(() -> registry.dataSource("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown DataSource name: nope");
    }

    @Test
    void dialectResolution_explicitOverride_wins() {
        DataSourceEntry e = entry("jdbc:h2:mem:dialect-override", null);
        e.setDialect("oracle");
        DbDialect d = DefaultDataSourceRegistry.resolveDialect(e);
        assertThat(d).isInstanceOf(OracleDialect.class);
    }

    @Test
    void dialectResolution_urlInferenceForOracle() {
        DataSourceEntry e = entry("jdbc:oracle:thin:@host:1521:ORCL", null);
        DbDialect d = DefaultDataSourceRegistry.resolveDialect(e);
        assertThat(d).isInstanceOf(OracleDialect.class);
    }

    @Test
    void dialectResolution_defaultsToMariaDbForH2() {
        DataSourceEntry e = entry("jdbc:h2:mem:dialect-default", null);
        DbDialect d = DefaultDataSourceRegistry.resolveDialect(e);
        assertThat(d).isInstanceOf(MariaDbDialect.class);
    }

    @Test
    void snapshot_includesAllRegisteredEntries() {
        primaryDs = h2DataSource("primary-snap");
        Station8DataSourcesProperties props = props(Map.of(
                "ext", entry("jdbc:h2:mem:snap-ext;DB_CLOSE_DELAY=-1", null)
        ));
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props,
                "jdbc:h2:mem:primary-snap", "sa");

        var snap = registry.snapshot();
        assertThat(snap).hasSize(2);
        assertThat(snap).extracting(DataSourceInfo::name)
                .containsExactlyInAnyOrder("primary", "ext");
        assertThat(snap).allSatisfy(info -> {
            assertThat(info.healthy()).isTrue();
            // 풀 통계는 H2 + Hikari 환경에서 가용 — primary는 외부 풀이라 -1일 수 있음
            // (registry가 풀 MXBean을 못 보는 경우)
        });
    }

    @Test
    void register_addsDynamicEntry_andCanQuery() {
        primaryDs = h2DataSource("primary-reg");
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props(Map.of()),
                "jdbc:h2:mem:primary-reg", "sa");

        DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                "added", "jdbc:h2:mem:reg-added;DB_CLOSE_DELAY=-1",
                "sa", "", "org.h2.Driver", null, Map.of());
        DataSourceRegistry.TestResult result = registry.register(spec);

        assertThat(result.success()).isTrue();
        assertThat(registry.names()).contains("added");
        assertThat(registry.sourceOf("added")).isEqualTo(DataSourceRegistry.Source.DYNAMIC);
        assertThat(registry.jdbc("added").queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void register_rejectsPrimaryName() {
        primaryDs = h2DataSource("primary-reject");
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props(Map.of()),
                "jdbc:h2:mem:primary-reject", "sa");

        DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                "primary", "jdbc:h2:mem:nope", "sa", "", null, null, Map.of());
        assertThatThrownBy(() -> registry.register(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'primary' is reserved");
    }

    @Test
    void register_rejectsDuplicateName() {
        primaryDs = h2DataSource("primary-dup");
        Station8DataSourcesProperties props = props(Map.of(
                "static-existing", entry("jdbc:h2:mem:dup-static;DB_CLOSE_DELAY=-1", null)
        ));
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props,
                "jdbc:h2:mem:primary-dup", "sa");

        DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                "static-existing", "jdbc:h2:mem:dup-attempt", "sa", "", null, null, Map.of());
        assertThatThrownBy(() -> registry.register(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered")
                .hasMessageContaining("STATIC");
    }

    @Test
    void swap_replacesDynamicPool_andClosesOld() {
        primaryDs = h2DataSource("primary-swap");
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props(Map.of()),
                "jdbc:h2:mem:primary-swap", "sa");
        registry.register(new DataSourceRegistry.DynamicSpec(
                "swappable", "jdbc:h2:mem:swap-v1;DB_CLOSE_DELAY=-1",
                "sa", "", "org.h2.Driver", null, Map.of()));
        DataSource oldDs = registry.dataSource("swappable");

        registry.swap(new DataSourceRegistry.DynamicSpec(
                "swappable", "jdbc:h2:mem:swap-v2;DB_CLOSE_DELAY=-1",
                "sa", "", "org.h2.Driver", null, Map.of()));

        DataSource newDs = registry.dataSource("swappable");
        assertThat(newDs).isNotSameAs(oldDs);
        assertThat(((com.zaxxer.hikari.HikariDataSource) oldDs).isClosed()).isTrue();
        assertThat(registry.info("swappable").url()).contains("swap-v2");
    }

    @Test
    void swap_rejectsStaticAndPrimary() {
        primaryDs = h2DataSource("primary-swap-reject");
        Station8DataSourcesProperties props = props(Map.of(
                "static-only", entry("jdbc:h2:mem:swap-static;DB_CLOSE_DELAY=-1", null)
        ));
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props,
                "jdbc:h2:mem:primary-swap-reject", "sa");

        DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                "static-only", "jdbc:h2:mem:nope", "sa", "", null, null, Map.of());
        assertThatThrownBy(() -> registry.swap(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot swap STATIC");

        DataSourceRegistry.DynamicSpec primarySpec = new DataSourceRegistry.DynamicSpec(
                "primary", "jdbc:h2:mem:nope", "sa", "", null, null, Map.of());
        assertThatThrownBy(() -> registry.swap(primarySpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot swap PRIMARY");
    }

    @Test
    void unregister_removesDynamic_andClosesPool() {
        primaryDs = h2DataSource("primary-unreg");
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props(Map.of()),
                "jdbc:h2:mem:primary-unreg", "sa");
        registry.register(new DataSourceRegistry.DynamicSpec(
                "to-remove", "jdbc:h2:mem:remove-me;DB_CLOSE_DELAY=-1",
                "sa", "", "org.h2.Driver", null, Map.of()));
        DataSource ds = registry.dataSource("to-remove");

        registry.unregister("to-remove");

        assertThat(registry.names()).doesNotContain("to-remove");
        assertThat(registry.sourceOf("to-remove")).isEqualTo(DataSourceRegistry.Source.NONE);
        assertThat(((com.zaxxer.hikari.HikariDataSource) ds).isClosed()).isTrue();
    }

    @Test
    void sourceOf_distinguishesPrimaryStaticDynamicNone() {
        primaryDs = h2DataSource("primary-source-test");
        Station8DataSourcesProperties props = props(Map.of(
                "static-x", entry("jdbc:h2:mem:src-static;DB_CLOSE_DELAY=-1", null)
        ));
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props,
                "jdbc:h2:mem:primary-source-test", "sa");
        registry.register(new DataSourceRegistry.DynamicSpec(
                "dynamic-x", "jdbc:h2:mem:src-dynamic;DB_CLOSE_DELAY=-1",
                "sa", "", "org.h2.Driver", null, Map.of()));

        assertThat(registry.sourceOf("primary")).isEqualTo(DataSourceRegistry.Source.PRIMARY);
        assertThat(registry.sourceOf("static-x")).isEqualTo(DataSourceRegistry.Source.STATIC);
        assertThat(registry.sourceOf("dynamic-x")).isEqualTo(DataSourceRegistry.Source.DYNAMIC);
        assertThat(registry.sourceOf("nope")).isEqualTo(DataSourceRegistry.Source.NONE);
    }

    @Test
    void close_closesOnlyOwnedSecondaryPools_primaryUntouched() {
        primaryDs = h2DataSource("primary-close");
        Station8DataSourcesProperties props = props(Map.of(
                "owned", entry("jdbc:h2:mem:close-secondary;DB_CLOSE_DELAY=-1", null)
        ));
        registry = new DefaultDataSourceRegistry(
                primaryDs, new MariaDbDialect(), props,
                "jdbc:h2:mem:primary-close", "sa");

        registry.close();
        assertThat(primaryDs.isClosed()).isFalse(); // 외부 라이프사이클 — 본 클래스가 안 닫음
        registry = null; // afterEach에서 중복 close 방지
    }

    // ---- helpers ----

    private static HikariDataSource h2DataSource(String name) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setPoolName("test-" + name);
        return new HikariDataSource(cfg);
    }

    private static DataSourceEntry entry(String url, String dialect) {
        DataSourceEntry e = new DataSourceEntry();
        e.setUrl(url);
        e.setUsername("sa");
        e.setPassword("");
        e.setDriverClassName("org.h2.Driver");
        if (dialect != null) e.setDialect(dialect);
        return e;
    }

    private static Station8DataSourcesProperties props(Map<String, DataSourceEntry> entries) {
        Station8DataSourcesProperties p = new Station8DataSourcesProperties();
        Map<String, DataSourceEntry> ds = p.getDatasources();
        ds.putAll(new LinkedHashMap<>(entries));
        return p;
    }
}
