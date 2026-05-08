package com.station8.engine.core;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.WorkflowSchedule;
import com.station8.engine.repository.JdbcActivityRepository;
import com.station8.engine.repository.JdbcWorkflowDefinitionRepository;
import com.station8.engine.repository.JdbcWorkflowScheduleRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowScheduler 통합 테스트 — H2 + 실제 schema 적용.
 *
 * 시나리오:
 *  - 만료된 스케줄 1건 → 인스턴스 생성 + 시작 노드 PENDING + nextRunDt 갱신
 *  - 일시중지(PAUSED='Y')는 폴링 무시
 *  - 미래 nextRunDt는 폴링 무시
 *  - 잘못된 cron은 1시간 뒤로 fallback (테스트는 nextFromCron 단독 검증)
 */
class WorkflowSchedulerTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcActivityRepository activityRepo;
    private static JdbcWorkflowDefinitionRepository defRepo;
    private static JdbcWorkflowScheduleRepository scheduleRepo;
    private static DagInterpreter interpreter;
    private static WorkflowScheduler scheduler;
    private static TransactionTemplate tx;

    private static final DbDialect H2_DIALECT = new DbDialect() {
        @Override public String limit(int limit) { return " FETCH FIRST " + limit + " ROWS ONLY"; }
        @Override public String currentTimestamp() { return "CURRENT_TIMESTAMP"; }
    };

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:scheduler_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("sql/schema-h2.sql"));
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        activityRepo = new JdbcActivityRepository(jdbcTemplate, H2_DIALECT);
        defRepo = new JdbcWorkflowDefinitionRepository(jdbcTemplate);
        scheduleRepo = new JdbcWorkflowScheduleRepository(jdbcTemplate, H2_DIALECT);

        DagValidator validator = new DagValidator();
        WorkflowRegistry stubRegistry = new WorkflowRegistry() {
            @Override public Set<String> getActivityNames() { return Set.of("A"); }
        };
        interpreter = new DagInterpreter(defRepo, activityRepo, validator, stubRegistry);
        scheduler = new WorkflowScheduler(scheduleRepo, interpreter, jdbcTemplate);

        // SKIP LOCKED + @Transactional이 동작하도록 트랜잭션 관리자 준비
        PlatformTransactionManager tm = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(tm);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM H_WF_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_WF_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_WF_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_WF_EDGE");
        jdbcTemplate.execute("DELETE FROM U_WF_NODE");
        jdbcTemplate.execute("DELETE FROM U_WF_DEFINITION");

        // 단일 노드 정의 (시작이자 종료) — DAG 검증 통과
        jdbcTemplate.update("""
                INSERT INTO U_WF_DEFINITION
                  (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('def-sched', 'CronDef', 1, 'Y', 'Y', 'Y', 'N')
                """);
        jdbcTemplate.update("""
                INSERT INTO U_WF_NODE
                  (ID, DEFINITION_ID, ACTIVITY_NM, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('n-only', 'def-sched', 'A', 'Y', 'Y', 'N')
                """);
    }

    @Test
    void due_schedule_triggers_instance_and_advances_nextRun() {
        scheduleRepo.insert(new WorkflowSchedule(
                "sch-due", "def-sched", "0 */1 * * * *",
                LocalDateTime.now().minusMinutes(1), null,
                "N", null, "Y", "Y", "N", null, "test", null, null));

        List<String> triggered = tx.execute(status -> scheduler.pollOnce(10));
        assertNotNull(triggered);
        assertEquals(1, triggered.size(), "만료 스케줄 1개 트리거되어야 함");

        // 인스턴스 + 시작 노드 PENDING 검증
        String instanceId = triggered.get(0);
        List<ActivityExecution> activities = activityRepo.findActivitiesByInstanceId(instanceId);
        assertEquals(1, activities.size());
        assertEquals("PENDING", activities.get(0).statusSt());
        assertEquals("n-only", activities.get(0).nodeId());

        // nextRunDt가 미래로 갱신
        WorkflowSchedule after = scheduleRepo.findById("sch-due");
        assertNotNull(after.nextRunDt());
        assertTrue(after.nextRunDt().isAfter(LocalDateTime.now()),
                "nextRunDt가 현재 이후여야 함: " + after.nextRunDt());
        assertNotNull(after.lastRunDt());
    }

    @Test
    void paused_and_future_schedules_are_skipped() {
        scheduleRepo.insert(new WorkflowSchedule(
                "sch-paused", "def-sched", "* * * * * *",
                LocalDateTime.now().minusMinutes(1), null,
                "Y", null, "Y", "Y", "N", null, "test", null, null));
        scheduleRepo.insert(new WorkflowSchedule(
                "sch-future", "def-sched", "* * * * * *",
                LocalDateTime.now().plusHours(1), null,
                "N", null, "Y", "Y", "N", null, "test", null, null));

        List<String> triggered = tx.execute(status -> scheduler.pollOnce(10));
        assertEquals(0, triggered.size(), "일시중지/미래 스케줄은 트리거되지 않아야 함");
    }

    @Test
    void invalid_cron_falls_back_to_one_hour_later() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 7, 10, 0, 0);
        LocalDateTime next = WorkflowScheduler.nextFromCron("not a cron expr", base);
        assertEquals(base.plusHours(1), next);
    }

    @Test
    void cron_expression_parses_to_correct_next() {
        // "0 */5 * * * *" — 매 5분 0초
        LocalDateTime base = LocalDateTime.of(2026, 5, 7, 10, 0, 30);
        LocalDateTime next = WorkflowScheduler.nextFromCron("0 */5 * * * *", base);
        assertEquals(LocalDateTime.of(2026, 5, 7, 10, 5, 0), next);
    }

    @Test
    void multiple_due_schedules_are_all_triggered() {
        for (int i = 0; i < 3; i++) {
            scheduleRepo.insert(new WorkflowSchedule(
                    "sch-batch-" + i, "def-sched", "0 */10 * * * *",
                    LocalDateTime.now().minusSeconds(10 + i), null,
                    "N", null, "Y", "Y", "N", null, "test", null, null));
        }
        List<String> triggered = tx.execute(status -> scheduler.pollOnce(10));
        assertEquals(3, triggered.size());
        triggered.forEach(id -> assertNotNull(activityRepo.findActivitiesByInstanceId(id)));
    }

    @Test
    void limit_caps_batch_size() {
        for (int i = 0; i < 5; i++) {
            scheduleRepo.insert(new WorkflowSchedule(
                    "sch-cap-" + i, "def-sched", "* * * * * *",
                    LocalDateTime.now().minusSeconds(10 + i), null,
                    "N", null, "Y", "Y", "N", null, "test", null, null));
        }
        List<String> triggered = tx.execute(status -> scheduler.pollOnce(2));
        assertEquals(2, triggered.size(), "limit=2이면 2건만 처리");
    }
}
