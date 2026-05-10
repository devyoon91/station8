package com.station8.engine.repository;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.LineInstance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #137 — JdbcActivityRepository 신규 필터 (다중 status / 날짜 범위 / 정렬) 통합 테스트.
 */
class JdbcActivityRepositoryFilterTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcActivityRepository repo;

    private static final DbDialect H2_DIALECT = new DbDialect() {
        @Override public String limit(int limit) { return " FETCH FIRST " + limit + " ROWS ONLY"; }
        @Override public String offsetLimit(int offset, int limit) {
            return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }
        @Override public String currentTimestamp() { return "CURRENT_TIMESTAMP"; }
    };

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:filter_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator pop = new ResourceDatabasePopulator();
        pop.addScript(new ClassPathResource("sql/schema-h2.sql"));
        pop.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        repo = new JdbcActivityRepository(jdbcTemplate, H2_DIALECT);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        // 시드 — 5개 인스턴스, 다양한 상태 + 시작일
        seed("inst-1", "FlowA", "RUNNING", LocalDateTime.of(2026, 5, 1, 10, 0));
        seed("inst-2", "FlowA", "COMPLETED", LocalDateTime.of(2026, 5, 5, 10, 0));
        seed("inst-3", "FlowB", "FAILED", LocalDateTime.of(2026, 5, 7, 10, 0));
        seed("inst-4", "FlowB", "COMPLETED", LocalDateTime.of(2026, 5, 9, 10, 0));
        seed("inst-5", "FlowC", "TERMINATED", LocalDateTime.of(2026, 5, 10, 10, 0));
    }

    @Test
    void multiStatus_filtersByIn() {
        InstanceQueryFilter f = new InstanceQueryFilter(
                null, List.of("RUNNING", "FAILED"), null, null, null, null, null);
        List<LineInstance> rows = repo.findInstancesPage(f, 0, 100);
        assertThat(rows).extracting(LineInstance::id)
                .containsExactlyInAnyOrder("inst-1", "inst-3");
        assertThat(repo.countInstances(f)).isEqualTo(2L);
    }

    @Test
    void singleStatus_legacyApi_stillWorks() {
        // 후방 호환 — default 메서드로 위임
        List<LineInstance> rows = repo.findInstancesPage("FlowA", "COMPLETED", null, 0, 100);
        assertThat(rows).extracting(LineInstance::id).containsExactly("inst-2");
        assertThat(repo.countInstances("FlowA", "COMPLETED", null)).isEqualTo(1L);
    }

    @Test
    void dateRange_inclusiveBothEnds() {
        // 2026-05-05 ~ 2026-05-09 → inst-2, inst-3, inst-4
        InstanceQueryFilter f = new InstanceQueryFilter(
                null, null, null,
                LocalDateTime.of(2026, 5, 5, 0, 0),
                LocalDateTime.of(2026, 5, 9, 23, 59, 59),
                null, null);
        List<LineInstance> rows = repo.findInstancesPage(f, 0, 100);
        assertThat(rows).extracting(LineInstance::id)
                .containsExactlyInAnyOrder("inst-2", "inst-3", "inst-4");
    }

    @Test
    void sortByStartDt_ascending() {
        InstanceQueryFilter f = new InstanceQueryFilter(
                null, null, null, null, null, "START_DT", "ASC");
        List<LineInstance> rows = repo.findInstancesPage(f, 0, 100);
        assertThat(rows).extracting(LineInstance::id)
                .containsExactly("inst-1", "inst-2", "inst-3", "inst-4", "inst-5");
    }

    @Test
    void sortByStartDt_descending() {
        InstanceQueryFilter f = new InstanceQueryFilter(
                null, null, null, null, null, "START_DT", "DESC");
        List<LineInstance> rows = repo.findInstancesPage(f, 0, 100);
        assertThat(rows).extracting(LineInstance::id)
                .containsExactly("inst-5", "inst-4", "inst-3", "inst-2", "inst-1");
    }

    @Test
    void multiStatus_combinedWithDateAndWorkflow() {
        // FlowB + (RUNNING|FAILED|COMPLETED) + 5/7~5/9
        InstanceQueryFilter f = new InstanceQueryFilter(
                "FlowB", List.of("RUNNING", "FAILED", "COMPLETED"), null,
                LocalDateTime.of(2026, 5, 7, 0, 0),
                LocalDateTime.of(2026, 5, 9, 23, 59, 59),
                "START_DT", "ASC");
        List<LineInstance> rows = repo.findInstancesPage(f, 0, 100);
        assertThat(rows).extracting(LineInstance::id).containsExactly("inst-3", "inst-4");
    }

    @Test
    void invalidSortBy_fallsBackToRegDt() {
        // SQL 인젝션 방어 — 잘못된 sortBy는 record가 REG_DT로 정규화
        InstanceQueryFilter f = new InstanceQueryFilter(
                null, null, null, null, null, "; DROP TABLE U_LINE_INSTANCE; --", null);
        assertThat(f.sortBy()).isEqualTo("REG_DT");
        // 쿼리도 정상 실행
        List<LineInstance> rows = repo.findInstancesPage(f, 0, 100);
        assertThat(rows).hasSize(5);
    }

    private void seed(String id, String workflow, String status, LocalDateTime startDt) {
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, ?, 'Y', 'Y', 'N', ?, CURRENT_TIMESTAMP)
            """, id, workflow, status, java.sql.Timestamp.valueOf(startDt));
    }
}
