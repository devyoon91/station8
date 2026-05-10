package com.station8.engine.repository;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.DlqEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #137 — DLQ 신규 필터 (날짜 범위 / activity LIKE / errorMessage LIKE / 다중 status / 정렬) 통합 테스트.
 */
class JdbcDlqRepositoryFilterTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcDlqRepository repo;

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
        dataSource.setUrl("jdbc:h2:mem:dlq_filter_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator pop = new ResourceDatabasePopulator();
        pop.addScript(new ClassPathResource("sql/schema-h2.sql"));
        pop.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        repo = new JdbcDlqRepository(jdbcTemplate, H2_DIALECT);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        // FK 만족 — 더미 instance + execution 먼저
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES ('inst-x', 'dummy', 'FAILED', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        jdbcTemplate.update("""
            INSERT INTO H_LINE_ACTIVITY_EXECUTION
              (ID, INSTANCE_ID, ACTIVITY_NAME, STATUS_ST, RETRY_CNT,
               USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES ('exec-x', 'inst-x', 'dummy', 'FAILED', 0,
                    'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
        seed("dlq-1", "FlowA", "Validate", "NEW", "Timeout after 30s",
                LocalDateTime.of(2026, 5, 1, 10, 0));
        seed("dlq-2", "FlowA", "Process", "REQUEUED", "NullPointerException at line 42",
                LocalDateTime.of(2026, 5, 5, 10, 0));
        seed("dlq-3", "FlowB", "Validate", "NEW", "ValidationError: required field missing",
                LocalDateTime.of(2026, 5, 7, 10, 0));
        seed("dlq-4", "FlowB", "Charge", "DISCARDED", "Timeout after 60s",
                LocalDateTime.of(2026, 5, 10, 10, 0));
    }

    @Test
    void activityName_likeFilter() {
        DlqQueryFilter f = new DlqQueryFilter(null, "Validate", null, null, null, null, null, null);
        List<DlqEntry> rows = repo.findPage(f, 0, 100);
        assertThat(rows).extracting(DlqEntry::id).containsExactlyInAnyOrder("dlq-1", "dlq-3");
    }

    @Test
    void errorMessage_likeFilter() {
        DlqQueryFilter f = new DlqQueryFilter(null, null, "Timeout", null, null, null, null, null);
        List<DlqEntry> rows = repo.findPage(f, 0, 100);
        assertThat(rows).extracting(DlqEntry::id).containsExactlyInAnyOrder("dlq-1", "dlq-4");
    }

    @Test
    void dlqStatus_multiSelect() {
        DlqQueryFilter f = new DlqQueryFilter(null, null, null,
                List.of("NEW", "REQUEUED"), null, null, null, null);
        List<DlqEntry> rows = repo.findPage(f, 0, 100);
        assertThat(rows).extracting(DlqEntry::id).containsExactlyInAnyOrder("dlq-1", "dlq-2", "dlq-3");
    }

    @Test
    void failedAt_dateRange_inclusive() {
        DlqQueryFilter f = new DlqQueryFilter(null, null, null, null,
                LocalDateTime.of(2026, 5, 5, 0, 0),
                LocalDateTime.of(2026, 5, 7, 23, 59, 59),
                null, null);
        List<DlqEntry> rows = repo.findPage(f, 0, 100);
        assertThat(rows).extracting(DlqEntry::id).containsExactlyInAnyOrder("dlq-2", "dlq-3");
    }

    @Test
    void sortByActivity_ascending() {
        DlqQueryFilter f = new DlqQueryFilter(null, null, null, null, null, null, "ACTIVITY_NAME", "ASC");
        List<DlqEntry> rows = repo.findPage(f, 0, 100);
        assertThat(rows).extracting(DlqEntry::activityName)
                .containsExactly("Charge", "Process", "Validate", "Validate");
    }

    @Test
    void legacyApi_returnsAll() {
        // 후방 호환 — 인자 없는 findPage / count
        assertThat(repo.findPage(0, 100)).hasSize(4);
        assertThat(repo.count()).isEqualTo(4L);
    }

    @Test
    void combinedFilters_workflowActivityDate() {
        // FlowA + Validate + 5/1
        DlqQueryFilter f = new DlqQueryFilter(
                "FlowA", "Validate", null, null,
                LocalDateTime.of(2026, 5, 1, 0, 0),
                LocalDateTime.of(2026, 5, 1, 23, 59, 59),
                null, null);
        List<DlqEntry> rows = repo.findPage(f, 0, 100);
        assertThat(rows).extracting(DlqEntry::id).containsExactly("dlq-1");
    }

    private void seed(String id, String workflow, String activity, String status, String error,
                      LocalDateTime failedAt) {
        jdbcTemplate.update("""
            INSERT INTO H_LINE_DLQ
              (ID, INSTANCE_ID, EXECUTION_ID, WORKFLOW_NAME, ACTIVITY_NAME,
               DLQ_STATUS_ST, ERROR_MESSAGE, RETRY_CNT, FAILED_AT_DT,
               USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID)
            VALUES (?, 'inst-x', 'exec-x', ?, ?, ?, ?, 3, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'test')
            """, id, workflow, activity, status, error, java.sql.Timestamp.valueOf(failedAt));
    }
}
