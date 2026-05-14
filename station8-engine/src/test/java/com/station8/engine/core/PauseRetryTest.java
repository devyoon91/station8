package com.station8.engine.core;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineStation;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.repository.JdbcActivityRepository;
import com.station8.engine.repository.JdbcLineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #139 — Pause / Unpause / activity retry 통합 테스트.
 *
 * <p>시나리오:</p>
 * <ul>
 *   <li>pauseLine: RUNNING → PAUSED</li>
 *   <li>pauseLine: 비-RUNNING 상태에서 IllegalStateException</li>
 *   <li>워커 폴링은 PAUSED 인스턴스의 PENDING 활동을 잡지 않음 (EXISTS 필터)</li>
 *   <li>unpauseLine: PAUSED → RUNNING + COMPLETED 노드의 fan-out 재평가</li>
 *   <li>retryActivity: 단일 FAILED 활동만 PENDING으로 reset (다른 활동 영향 X)</li>
 *   <li>retryActivity: 인스턴스 PAUSED일 때 거부</li>
 * </ul>
 */
class PauseRetryTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcActivityRepository activityRepo;
    private static JdbcLineDefinitionRepository defRepo;
    private static JdbcLineExecutor executor;
    private static DagInterpreter interpreter;
    private static TransactionTemplate tx;
    private static final JsonUtil jsonUtil = new JsonUtil();

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
        dataSource.setUrl("jdbc:h2:mem:pause_retry_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator pop = new ResourceDatabasePopulator();
        pop.addScript(new ClassPathResource("sql/schema-h2.sql"));
        pop.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        activityRepo = new JdbcActivityRepository(jdbcTemplate, H2_DIALECT);
        defRepo = new JdbcLineDefinitionRepository(jdbcTemplate, H2_DIALECT);

        EdgeConditionEvaluator evaluator = new EdgeConditionEvaluator(jsonUtil);
        DagValidator validator = new DagValidator(evaluator);
        LineRegistry stubRegistry = new LineRegistry() {
            @Override public Set<String> getActivityNames() { return Set.of("A", "B"); }
        };
        // 임시 placeholder executor로 interpreter 생성 후 진짜 executor 생성, 나중에 cycle 풀기
        executor = new JdbcLineExecutor(jdbcTemplate, activityRepo, jsonUtil);
        interpreter = new DagInterpreter(defRepo, activityRepo, validator, stubRegistry, evaluator, executor);
        // 진짜 executor — DagInterpreter 주입
        executor = new JdbcLineExecutor(jdbcTemplate, activityRepo, jsonUtil, interpreter);

        PlatformTransactionManager tm = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(tm);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    // ===== pauseLine =====

    @Test
    void pauseLine_running_transitionsToPaused() {
        String inst = seedInstance("FlowP1", "RUNNING");

        executor.pauseLine(inst);

        assertThat(activityRepo.findInstanceById(inst).statusSt()).isEqualTo("PAUSED");
    }

    @Test
    void pauseLine_notRunning_throws() {
        String inst = seedInstance("FlowP2", "COMPLETED");
        assertThatThrownBy(() -> executor.pauseLine(inst))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }

    // ===== Polling SQL filter =====

    @Test
    void polling_skipsPendingActivitiesOfPausedInstances() {
        String pausedInst = seedInstance("FlowPaused", "PAUSED");
        String runningInst = seedInstance("FlowRunning", "RUNNING");

        // 둘 다 PENDING 활동을 추가
        seedPendingActivity(pausedInst, "A");
        String runningExecId = seedPendingActivity(runningInst, "B");

        // 폴링은 RUNNING 인스턴스의 활동만 픽업해야 함
        List<ActivityExecution> picked = tx.execute(s ->
                activityRepo.findPendingActivitiesWithLock(10));

        assertThat(picked).extracting(ActivityExecution::id)
                .containsExactly(runningExecId);
    }

    // ===== unpauseLine =====

    @Test
    void unpauseLine_paused_transitionsToRunning() {
        String inst = seedInstance("FlowU1", "PAUSED");

        executor.unpauseLine(inst);

        assertThat(activityRepo.findInstanceById(inst).statusSt()).isEqualTo("RUNNING");
    }

    @Test
    void unpauseLine_notPaused_throws() {
        String inst = seedInstance("FlowU2", "RUNNING");
        assertThatThrownBy(() -> executor.unpauseLine(inst))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAUSED");
    }

    @Test
    void unpauseLine_reEvaluatesFanout_promotesOrphanedSuccessor() {
        // DAG: A → B. A 완료 + 인스턴스 PAUSED일 때 B는 WAITING_DEPENDENCIES 그대로.
        // Unpause 후 fan-out 재평가 → B를 PENDING으로 promote
        String defId = "def-" + UUID.randomUUID();
        defRepo.insertDefinition(new LineDefinition(defId, "FlowFanout", null, 1, "Y",
                null, null, null, null,  // #168 projectId
                "Y", "Y", "N", null, "test", null, null));
        defRepo.insertNode(new LineStation("n-a", defId, "A", "A", null, null, 0, 0,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertNode(new LineStation("n-b", defId, "B", "B", null, null, 0, 0,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertEdge(new LineTrack("e1", defId, "n-a", "n-b", null,
                "Y", "Y", "N", null, null, null, null));

        String inst = seedInstance("FlowFanout", "PAUSED");
        // A는 COMPLETED, B는 WAITING (Pause 동안 fan-out이 차단됐다고 가정)
        seedActivity(inst, "n-a", "A", "COMPLETED");
        seedActivity(inst, "n-b", "B", "WAITING_DEPENDENCIES");

        executor.unpauseLine(inst);

        // B가 PENDING으로 promote돼야 함
        ActivityExecution b = activityRepo.findByInstanceAndNode(inst, "n-b");
        assertThat(b.statusSt()).isEqualTo("PENDING");
    }

    // ===== retryActivity =====

    @Test
    void retryActivity_failedActivity_resetsToPending() {
        String inst = seedInstance("FlowRetry1", "RUNNING");
        String execId = seedActivity(inst, null, "A", "FAILED");

        executor.retryActivity(execId);

        assertThat(activityRepo.findById(execId).statusSt()).isEqualTo("PENDING");
    }

    @Test
    void retryActivity_otherActivities_unchanged() {
        String inst = seedInstance("FlowRetry2", "RUNNING");
        String failed = seedActivity(inst, null, "A", "FAILED");
        String running = seedActivity(inst, null, "B", "RUNNING");

        executor.retryActivity(failed);

        assertThat(activityRepo.findById(failed).statusSt()).isEqualTo("PENDING");
        assertThat(activityRepo.findById(running).statusSt()).isEqualTo("RUNNING");
    }

    @Test
    void retryActivity_notFailed_throws() {
        String inst = seedInstance("FlowRetry3", "RUNNING");
        String execId = seedActivity(inst, null, "A", "RUNNING");

        assertThatThrownBy(() -> executor.retryActivity(execId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void retryActivity_pausedInstance_throws() {
        String inst = seedInstance("FlowRetry4", "PAUSED");
        String execId = seedActivity(inst, null, "A", "FAILED");

        assertThatThrownBy(() -> executor.retryActivity(execId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }

    // ===== 헬퍼 =====

    private String seedInstance(String workflowName, String status) {
        String id = "inst-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, id, workflowName, status);
        return id;
    }

    private String seedPendingActivity(String instanceId, String activityName) {
        return seedActivity(instanceId, null, activityName, "PENDING");
    }

    private String seedActivity(String instanceId, String nodeId, String activityName, String status) {
        String id = "exec-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO H_LINE_ACTIVITY_EXECUTION
              (ID, INSTANCE_ID, NODE_ID, ACTIVITY_NAME, STATUS_ST, RETRY_CNT,
               USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, ?, ?, ?, 0,
                    'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, id, instanceId, nodeId, activityName, status);
        return id;
    }
}
