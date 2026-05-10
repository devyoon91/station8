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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #152 — 엣지 조건식 통합 테스트.
 *
 * <p>시나리오:</p>
 * <ul>
 *   <li>조건 만족 → 후행 활성화 (PENDING)</li>
 *   <li>조건 불만족 (단일 후행) → 인스턴스 FAILED</li>
 *   <li>분기 — 조건 만족하는 엣지만 활성화</li>
 *   <li>분기 모두 불만족 → 인스턴스 FAILED</li>
 *   <li>조건 평가 예외 (잘못된 SpEL) → 인스턴스 FAILED</li>
 * </ul>
 */
class DagInterpreterConditionTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcActivityRepository activityRepo;
    private static JdbcLineDefinitionRepository defRepo;
    private static DagInterpreter interpreter;
    private static JdbcLineExecutor lineExecutor;
    private static JsonUtil jsonUtil = new JsonUtil();

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
        dataSource.setUrl("jdbc:h2:mem:dag_cond_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator pop = new ResourceDatabasePopulator();
        pop.addScript(new ClassPathResource("sql/schema-h2.sql"));
        pop.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        activityRepo = new JdbcActivityRepository(jdbcTemplate, H2_DIALECT);
        defRepo = new JdbcLineDefinitionRepository(jdbcTemplate, H2_DIALECT);
        lineExecutor = new JdbcLineExecutor(jdbcTemplate, activityRepo, jsonUtil);

        EdgeConditionEvaluator evaluator = new EdgeConditionEvaluator(jsonUtil);
        DagValidator validator = new DagValidator(evaluator);
        LineRegistry stubRegistry = new LineRegistry() {
            @Override public Set<String> getActivityNames() { return Set.of("A", "B", "C"); }
        };
        interpreter = new DagInterpreter(defRepo, activityRepo, validator, stubRegistry,
                evaluator, lineExecutor);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    // ===== 시나리오 1: 단일 후행 + 조건 만족 → 정상 promote =====

    @Test
    void condition_met_promotesSuccessor() {
        // A → B (cond: success == true). 결과 success=true → B PENDING.
        String defId = setupLinearDef("FlowMet", "#result['success'] == true");
        String instanceId = "inst-met-" + System.nanoTime();
        startInstance(instanceId, "FlowMet");
        interpreter.startInstance(defId, instanceId, null);

        // A 완료 + outputData 셋
        completeActivity(instanceId, "n-a", "{\"success\":true}");
        interpreter.onNodeCompleted(instanceId, "n-a");

        ActivityExecution bExec = activityRepo.findByInstanceAndNode(instanceId, "n-b");
        assertThat(bExec.statusSt()).isEqualTo("PENDING");

        LineInstance inst = activityRepo.findInstanceById(instanceId);
        assertThat(inst.statusSt()).isEqualTo("RUNNING");
    }

    // ===== 시나리오 2: 단일 후행 + 조건 불만족 → 인스턴스 FAILED =====

    @Test
    void condition_failed_singleSuccessor_marksInstanceFailed() {
        String defId = setupLinearDef("FlowFailed", "#result['success'] == true");
        String instanceId = "inst-failed-" + System.nanoTime();
        startInstance(instanceId, "FlowFailed");
        interpreter.startInstance(defId, instanceId, null);

        completeActivity(instanceId, "n-a", "{\"success\":false}");
        interpreter.onNodeCompleted(instanceId, "n-a");

        // 후행은 여전히 WAITING (활성화 안 됨)
        ActivityExecution bExec = activityRepo.findByInstanceAndNode(instanceId, "n-b");
        assertThat(bExec.statusSt()).isIn("WAITING_DEPENDENCIES", "TERMINATED");

        // 인스턴스는 FAILED + 사유 기록
        LineInstance inst = activityRepo.findInstanceById(instanceId);
        assertThat(inst.statusSt()).isEqualTo("FAILED");
        assertThat(inst.outputData()).contains("failureReason");
        assertThat(inst.outputData()).contains("#result['success'] == true");
    }

    // ===== 시나리오 3: 분기 — 한쪽만 만족 =====

    @Test
    void branch_onlyMatchedEdgeActivated() {
        // A → B (cond: amount > 100), A → C (cond: amount <= 100). amount=50 → C만 활성화.
        String defId = setupBranchDef("FlowBranch",
                "#result['amount'] > 100",
                "#result['amount'] <= 100");
        String instanceId = "inst-branch-" + System.nanoTime();
        startInstance(instanceId, "FlowBranch");
        interpreter.startInstance(defId, instanceId, null);

        completeActivity(instanceId, "n-a", "{\"amount\":50}");
        interpreter.onNodeCompleted(instanceId, "n-a");

        assertThat(activityRepo.findByInstanceAndNode(instanceId, "n-b").statusSt())
                .isEqualTo("WAITING_DEPENDENCIES");
        assertThat(activityRepo.findByInstanceAndNode(instanceId, "n-c").statusSt())
                .isEqualTo("PENDING");

        assertThat(activityRepo.findInstanceById(instanceId).statusSt()).isEqualTo("RUNNING");
    }

    // ===== 시나리오 4: 분기 모두 불만족 → FAILED =====

    @Test
    void branch_allFailed_marksInstanceFailed() {
        String defId = setupBranchDef("FlowAllFail",
                "#result['amount'] > 1000",
                "#result['amount'] < 0");
        String instanceId = "inst-all-fail-" + System.nanoTime();
        startInstance(instanceId, "FlowAllFail");
        interpreter.startInstance(defId, instanceId, null);

        completeActivity(instanceId, "n-a", "{\"amount\":50}");
        interpreter.onNodeCompleted(instanceId, "n-a");

        LineInstance inst = activityRepo.findInstanceById(instanceId);
        assertThat(inst.statusSt()).isEqualTo("FAILED");
        assertThat(inst.outputData()).contains("All outgoing edges");
    }

    // ===== 시나리오 5: 평가 예외 → FAILED =====

    @Test
    void evaluationException_marksInstanceFailed() {
        // 결과가 number인데 string 비교 → SpEL 평가 예외 (D5=b)
        String defId = setupLinearDef("FlowEvalError", "#result['x'] > 'abc'");
        String instanceId = "inst-eval-" + System.nanoTime();
        startInstance(instanceId, "FlowEvalError");
        interpreter.startInstance(defId, instanceId, null);

        completeActivity(instanceId, "n-a", "{\"x\":5}");
        interpreter.onNodeCompleted(instanceId, "n-a");

        LineInstance inst = activityRepo.findInstanceById(instanceId);
        assertThat(inst.statusSt()).isEqualTo("FAILED");
        assertThat(inst.outputData()).contains("Edge condition evaluation failed");
    }

    // ===== 헬퍼 =====

    /** 정의 생성: A → B, B에 conditionExpr 부여 */
    private String setupLinearDef(String name, String conditionOnAB) {
        String defId = "def-" + System.nanoTime();
        defRepo.insertDefinition(new LineDefinition(
                defId, name, null, 1, "Y",
                null, null,
                "Y", "Y", "N",
                null, "test", null, null));
        defRepo.insertNode(new LineStation("n-a", defId, "A", "A", null, null, 0, 0,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertNode(new LineStation("n-b", defId, "B", "B", null, null, 0, 0,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertEdge(new LineTrack("e1", defId, "n-a", "n-b", conditionOnAB,
                "Y", "Y", "N", null, null, null, null));
        return defId;
    }

    /** 정의 생성: A → B (condAB), A → C (condAC) */
    private String setupBranchDef(String name, String condAB, String condAC) {
        String defId = "def-" + System.nanoTime();
        defRepo.insertDefinition(new LineDefinition(
                defId, name, null, 1, "Y",
                null, null,
                "Y", "Y", "N",
                null, "test", null, null));
        defRepo.insertNode(new LineStation("n-a", defId, "A", "A", null, null, 0, 0,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertNode(new LineStation("n-b", defId, "B", "B", null, null, 0, 0,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertNode(new LineStation("n-c", defId, "C", "C", null, null, 0, 0,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertEdge(new LineTrack("e1", defId, "n-a", "n-b", condAB,
                "Y", "Y", "N", null, null, null, null));
        defRepo.insertEdge(new LineTrack("e2", defId, "n-a", "n-c", condAC,
                "Y", "Y", "N", null, null, null, null));
        return defId;
    }

    private void startInstance(String instanceId, String workflowName) {
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, 'RUNNING', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId, workflowName);
    }

    /** 활동의 STATUS_ST = COMPLETED, OUTPUT_DATA = outputJson */
    private void completeActivity(String instanceId, String nodeId, String outputJson) {
        ActivityExecution exec = activityRepo.findByInstanceAndNode(instanceId, nodeId);
        ActivityExecution updated = new ActivityExecution(
                exec.id(), exec.instanceId(), exec.nodeId(), exec.activityName(),
                "COMPLETED", exec.inputData(), outputJson,
                null, null, exec.retryCnt(), null,
                exec.startDt(), java.time.LocalDateTime.now(),
                exec.useFl(), exec.viewFl(), exec.delFl(),
                exec.regDt(), exec.regId(), java.time.LocalDateTime.now(), "test");
        activityRepo.updateStatus(updated);
    }
}
