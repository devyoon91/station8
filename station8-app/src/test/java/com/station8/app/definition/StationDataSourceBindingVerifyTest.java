package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.annotation.BoundDataSource;
import com.station8.engine.core.ActivityArgumentResolver;
import com.station8.engine.datasource.DataSourceRegistry;
import com.station8.engine.repository.LineDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #113 Station 단위 DataSource 바인딩 verification — PR #116 머지 후 검증.
 *
 * <p>3개 시나리오를 실제 Spring 컨텍스트에서 검증:</p>
 * <ol>
 *   <li>API roundtrip — POST/getDefinition으로 datasourceBindings 보존</li>
 *   <li>{@code @BoundDataSource("primary")} → 실제 primary JdbcTemplate 주입</li>
 *   <li>role 누락 시 primary fallback (WARN 로그는 출력만 확인 — assert는 함수형 동작)</li>
 * </ol>
 */
@SpringBootTest(classes = Application.class)
class StationDataSourceBindingVerifyTest {

    @Autowired LineDefinitionService service;
    @Autowired ActivityArgumentResolver resolver;
    @Autowired DataSourceRegistry registry;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LineDefinitionRepository definitionRepository;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        // 테스트 격리
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    /**
     * 검증 1: API roundtrip — Map<String,String> 바인딩이 create + get 사이에 보존되는가.
     */
    @Test
    void datasourceBindings_persistsThroughCreateAndGetApi() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "BindingFlow",
                "#113 verification: bindings round-trip",
                List.of(new DagDefinitionRequest.NodeDef(
                        "n-bind", "BindStation", "MIGRATION_WRITE",
                        null, 0, 0,
                        Map.of("source", "ops-oracle", "target", "mart-mariadb")
                )),
                List.of()
        );

        String defId = service.createDefinition(req);
        DagDefinitionResponse fetched = service.getDefinition(defId);

        assertThat(fetched.nodes()).hasSize(1);
        DagDefinitionRequest.NodeDef gotNode = fetched.nodes().get(0);
        assertThat(gotNode.datasourceBindings())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("source", "ops-oracle", "target", "mart-mariadb"));

        // station record가 DB에서 직접 읽었을 때도 동일
        var station = definitionRepository.findStationById("n-bind");
        assertThat(station).isNotNull();
        assertThat(station.datasourceBindings())
                .contains("source").contains("ops-oracle")
                .contains("target").contains("mart-mariadb");
    }

    /**
     * 검증 2: {@code @BoundDataSource("primary")} → 실제 Spring primary JdbcTemplate 주입.
     * SELECT 1로 살아있는지도 동시 확인.
     */
    @Test
    void boundDataSourcePrimary_resolvesToActualPrimaryAndCanQuery() throws Exception {
        Probe probe = new Probe();
        Method m = Probe.class.getMethod("withPrimary", String.class, JdbcTemplate.class);
        var ctx = new ActivityArgumentResolver.Context("payload",
                Map.of("main", "primary"));

        Object[] args = resolver.resolve(m, ctx);

        assertThat(args).hasSize(2);
        assertThat(args[0]).isEqualTo("payload");
        assertThat(args[1]).isInstanceOf(JdbcTemplate.class);
        // resolver가 반환한 것이 registry의 primary와 동일 인스턴스
        assertThat(args[1]).isSameAs(registry.jdbc("primary"));

        // 실제 SELECT 1 — 살아있는 풀임을 확인
        Integer one = ((JdbcTemplate) args[1]).queryForObject("SELECT 1", Integer.class);
        assertThat(one).isEqualTo(1);

        // 액티비티 호출 시뮬레이션 — 실제 메서드 invoke
        String result = probe.withPrimary((String) args[0], (JdbcTemplate) args[1]);
        assertThat(result).isEqualTo("ok:1");
    }

    /**
     * 검증 3: 바인딩에 role이 없으면 primary fallback. WARN 로그가 출력되어야 하지만
     * assertion은 동작(=primary 주입)에만 둠.
     */
    @Test
    void missingRole_fallsBackToPrimary() throws Exception {
        Method m = Probe.class.getMethod("withPrimary", String.class, JdbcTemplate.class);

        // bindings 비어있음 — "main" role 매칭 실패 → primary fallback
        var ctx = new ActivityArgumentResolver.Context("x", Map.of());
        Object[] args = resolver.resolve(m, ctx);

        assertThat(args[1]).isSameAs(registry.jdbc("primary"));
    }

    /**
     * 검증 3-2: bindings에 등록 안 된 DS 이름을 적어도 primary fallback.
     */
    @Test
    void unknownDsName_fallsBackToPrimary() throws Exception {
        Method m = Probe.class.getMethod("withPrimary", String.class, JdbcTemplate.class);
        var ctx = new ActivityArgumentResolver.Context("x", Map.of("main", "ghost-name-not-registered"));

        Object[] args = resolver.resolve(m, ctx);

        assertThat(args[1]).isSameAs(registry.jdbc("primary"));
    }

    static class Probe {
        public String withPrimary(String input, @BoundDataSource("main") JdbcTemplate jdbc) {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return "ok:" + one;
        }
    }
}
