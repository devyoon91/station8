package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.core.PipelineGate;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.repository.LineDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #164 — PipelineGate 통합 테스트.
 *
 * <p>4-노드 직선 DAG: A → B → C → D. 두 인스턴스(prior / new)가 실행 중인 상황에서
 * 정책별 게이트 동작 검증.</p>
 */
@SpringBootTest(classes = Application.class)
class PipelineGateTest {

    @Autowired LineDefinitionService service;
    @Autowired LineDefinitionRepository definitionRepo;
    @Autowired PipelineGate pipelineGate;
    @Autowired JdbcTemplate jdbcTemplate;

    private String defId;
    private String wfName = "PipelineFlow";

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_TAG");
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    @Test
    void canDispatch_concurrent_alwaysAllows() {
        defId = createPipelineDef(wfName, "CONCURRENT");
        String prior = seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);
        seedActivityRunning(prior, "n-a", "A");

        // 새 인스턴스의 nodeA가 dispatch 시도 — CONCURRENT는 항상 통과
        boolean ok = pipelineGate.canDispatch(fresh, "n-a", wfName);
        assertThat(ok).isTrue();
    }

    @Test
    void pipeline1_blocksUntilPriorSameNodeCompleted() {
        defId = createPipelineDef(wfName, "PIPELINE_1");
        String prior = seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);

        // 선행이 nodeA RUNNING — 새 nodeA는 차단
        seedActivityRunning(prior, "n-a", "A");
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isFalse();

        // 선행 nodeA COMPLETED — 새 nodeA 통과
        completeActivity(prior, "n-a");
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isTrue();
    }

    @Test
    void pipeline2_blocksUntilPriorNextStepStarted() {
        defId = createPipelineDef(wfName, "PIPELINE_2");
        String prior = seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);

        // 선행이 nodeA만 시작 — 새 nodeA는 차단 (B가 시작 안 됨)
        seedActivityRunning(prior, "n-a", "A");
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isFalse();

        // 선행이 B 시작 — 새 A 통과
        seedActivityRunning(prior, "n-b", "B");
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isTrue();
    }

    @Test
    void pipeline2_lastNode_alwaysAllows() {
        defId = createPipelineDef(wfName, "PIPELINE_2");
        String prior = seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);
        seedActivityRunning(prior, "n-a", "A");

        // 새 인스턴스의 nodeD(마지막) — D+1 단계 노드 없음 → 통과
        assertThat(pipelineGate.canDispatch(fresh, "n-d", wfName)).isTrue();
    }

    @Test
    void pipeline3_blocksUntilPriorTwoStepsAheadStarted() {
        defId = createPipelineDef(wfName, "PIPELINE_3");
        String prior = seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);

        // 선행이 A,B 시작 — 새 A는 차단 (C가 안 됨)
        seedActivityRunning(prior, "n-a", "A");
        seedActivityRunning(prior, "n-b", "B");
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isFalse();

        // C 시작 — 새 A 통과
        seedActivityRunning(prior, "n-c", "C");
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isTrue();
    }

    @Test
    void pipeline_noPriorRunning_allowsImmediately() {
        defId = createPipelineDef(wfName, "PIPELINE_2");
        // 첫 인스턴스만 — 선행 없음
        String fresh = seedRunningInstance(wfName);
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isTrue();
    }

    @Test
    void pipeline_priorCompleted_doesNotGate() {
        defId = createPipelineDef(wfName, "PIPELINE_1");
        String prior = seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);
        seedActivityRunning(prior, "n-a", "A");

        // 선행을 COMPLETED 상태로 변경 — 더 이상 게이트 후보 아님
        jdbcTemplate.update("UPDATE U_LINE_INSTANCE SET STATUS_ST = 'COMPLETED' WHERE ID = ?", prior);
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isTrue();
    }

    @Test
    void pipeline_priorPaused_doesNotGate() {
        // 데드락 방지: PAUSED 인스턴스는 게이트 후보 아님
        defId = createPipelineDef(wfName, "PIPELINE_1");
        String prior = seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);
        seedActivityRunning(prior, "n-a", "A");

        jdbcTemplate.update("UPDATE U_LINE_INSTANCE SET STATUS_ST = 'PAUSED' WHERE ID = ?", prior);
        assertThat(pipelineGate.canDispatch(fresh, "n-a", wfName)).isTrue();
    }

    @Test
    void pipeline_unknownNodeId_safelyAllows() {
        defId = createPipelineDef(wfName, "PIPELINE_2");
        seedRunningInstance(wfName);
        String fresh = seedRunningInstance(wfName);

        // 정의에 없는 nodeId — step 매핑 안 됨 → 안전 통과
        assertThat(pipelineGate.canDispatch(fresh, "n-unknown", wfName)).isTrue();
    }

    // ===== 헬퍼 =====

    /** A → B → C → D 직선 DAG + 정책. */
    private String createPipelineDef(String name, String policy) {
        DagDefinitionRequest req = new DagDefinitionRequest(
                name, "test", null, null, policy, null,
                List.of(
                        new DagDefinitionRequest.NodeDef("n-a", "A", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("n-b", "B", "MIGRATION_WRITE", null, 100, 0, null),
                        new DagDefinitionRequest.NodeDef("n-c", "C", "MIGRATION_WRITE", null, 200, 0, null),
                        new DagDefinitionRequest.NodeDef("n-d", "D", "MIGRATION_WRITE", null, 300, 0, null)
                ),
                List.of(
                        new DagDefinitionRequest.EdgeDef("e-1", "n-a", "n-b", null),
                        new DagDefinitionRequest.EdgeDef("e-2", "n-b", "n-c", null),
                        new DagDefinitionRequest.EdgeDef("e-3", "n-c", "n-d", null)
                )
        );
        return service.createDefinition(req);
    }

    private String seedRunningInstance(String workflowName) {
        String id = "inst-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST,
                DEL_FL, REG_DT, START_DT)
            VALUES (?, ?, 'RUNNING', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, id, workflowName);
        return id;
    }

    /**
     * 활동 RUNNING 시드. service.createDefinition은 외부 NodeDef.nodeId를 그대로 U_LINE_STATION.ID로 사용하므로
     * "n-a" 같은 nodeId가 station ID와 같다.
     */
    private void seedActivityRunning(String instanceId, String nodeId, String activityName) {
        jdbcTemplate.update("""
            INSERT INTO H_LINE_ACTIVITY_EXECUTION
              (ID, INSTANCE_ID, NODE_ID, ACTIVITY_NAME, STATUS_ST,
               DEL_FL, REG_DT, START_DT)
            VALUES (?, ?, ?, ?, 'RUNNING', 'N',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, "exec-" + UUID.randomUUID(), instanceId, nodeId, activityName);
    }

    private void completeActivity(String instanceId, String nodeId) {
        jdbcTemplate.update("""
            UPDATE H_LINE_ACTIVITY_EXECUTION
            SET STATUS_ST = 'COMPLETED', END_DT = CURRENT_TIMESTAMP
            WHERE INSTANCE_ID = ? AND NODE_ID = ?
            """, instanceId, nodeId);
    }
}
