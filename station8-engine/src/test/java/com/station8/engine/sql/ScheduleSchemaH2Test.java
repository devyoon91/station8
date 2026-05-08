package com.station8.engine.sql;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.WorkflowSchedule;
import com.station8.engine.repository.JdbcWorkflowScheduleRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U_WF_SCHEDULE DDL 검증 + JdbcWorkflowScheduleRepository 기본 동작.
 */
class ScheduleSchemaH2Test {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcWorkflowScheduleRepository repository;

    private static final DbDialect H2_DIALECT = new DbDialect() {
        @Override public String limit(int limit) { return " FETCH FIRST " + limit + " ROWS ONLY"; }
        @Override public String currentTimestamp() { return "CURRENT_TIMESTAMP"; }
    };

    @BeforeAll
    static void applySchema() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:schedule_schema_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("sql/schema-h2.sql"));
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcWorkflowScheduleRepository(jdbcTemplate, H2_DIALECT);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM U_WF_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_WF_EDGE");
        jdbcTemplate.execute("DELETE FROM U_WF_NODE");
        jdbcTemplate.execute("DELETE FROM U_WF_DEFINITION");

        // 테스트용 정의 1개 생성 (FK 제약 만족)
        jdbcTemplate.update("""
                INSERT INTO U_WF_DEFINITION
                  (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('def-test', 'TestDef', 1, 'Y', 'Y', 'Y', 'N')
                """);
    }

    @Test
    void scheduleTableExists() throws Exception {
        boolean found = false;
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getTables(null, "PUBLIC", "U_WF_SCHEDULE", new String[]{"TABLE"})) {
                if (rs.next()) found = true;
            }
        }
        assertTrue(found, "U_WF_SCHEDULE 테이블이 존재해야 함");
    }

    @Test
    void requiredIndexesExist() throws Exception {
        Set<String> indexes = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getIndexInfo(null, "PUBLIC", "U_WF_SCHEDULE", false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null) indexes.add(name.toUpperCase());
            }
        }
        assertTrue(indexes.contains("U_WF_SCHEDULE_IDX01"), "PAUSED_FL/NEXT_RUN_DT 인덱스 필요");
        assertTrue(indexes.contains("U_WF_SCHEDULE_IDX02"), "DEFINITION_ID 인덱스 필요");
    }

    @Test
    void insertAndFindById() {
        WorkflowSchedule s = new WorkflowSchedule(
                "sch-1", "def-test", "0 */5 * * * *",
                LocalDateTime.now().plusMinutes(5), null,
                "N", null, "Y", "Y", "N", null, "test", null, null
        );
        repository.insert(s);

        WorkflowSchedule found = repository.findById("sch-1");
        assertNotNull(found);
        assertEquals("def-test", found.definitionId());
        assertEquals("0 */5 * * * *", found.cronExpr());
        assertEquals("N", found.pausedFl());
    }

    @Test
    void findDueWithLock_returns_only_expired_and_not_paused() {
        // 만료 (포함되어야 함)
        repository.insert(new WorkflowSchedule(
                "sch-due", "def-test", "* * * * * *",
                LocalDateTime.now().minusMinutes(1), null,
                "N", null, "Y", "Y", "N", null, "test", null, null));
        // 미래 (제외)
        repository.insert(new WorkflowSchedule(
                "sch-future", "def-test", "* * * * * *",
                LocalDateTime.now().plusHours(1), null,
                "N", null, "Y", "Y", "N", null, "test", null, null));
        // 일시중지 (제외)
        repository.insert(new WorkflowSchedule(
                "sch-paused", "def-test", "* * * * * *",
                LocalDateTime.now().minusMinutes(5), null,
                "Y", null, "Y", "Y", "N", null, "test", null, null));

        List<WorkflowSchedule> due = repository.findDueWithLock(10);
        assertEquals(1, due.size());
        assertEquals("sch-due", due.get(0).id());
    }

    @Test
    void markRun_updates_next_and_last() {
        repository.insert(new WorkflowSchedule(
                "sch-mr", "def-test", "* * * * * *",
                LocalDateTime.now().minusMinutes(1), null,
                "N", null, "Y", "Y", "N", null, "test", null, null));

        LocalDateTime nextRun = LocalDateTime.now().plusMinutes(5);
        LocalDateTime lastRun = LocalDateTime.now();
        repository.markRun("sch-mr", nextRun, lastRun);

        WorkflowSchedule s = repository.findById("sch-mr");
        assertNotNull(s.nextRunDt());
        assertNotNull(s.lastRunDt());
        assertTrue(s.nextRunDt().isAfter(LocalDateTime.now()));
    }

    @Test
    void setPaused_toggles_flag() {
        repository.insert(new WorkflowSchedule(
                "sch-p", "def-test", "* * * * * *",
                null, null, "N", null,
                "Y", "Y", "N", null, "test", null, null));

        repository.setPaused("sch-p", true);
        assertEquals("Y", repository.findById("sch-p").pausedFl());

        repository.setPaused("sch-p", false);
        assertEquals("N", repository.findById("sch-p").pausedFl());
    }

    @Test
    void softDelete_removes_from_findAll_and_findById() {
        repository.insert(new WorkflowSchedule(
                "sch-d", "def-test", "* * * * * *",
                null, null, "N", null,
                "Y", "Y", "N", null, "test", null, null));

        repository.softDelete("sch-d");

        assertNull(repository.findById("sch-d"));
        assertTrue(repository.findAll().stream().noneMatch(s -> "sch-d".equals(s.id())));
    }
}
