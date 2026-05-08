package com.station8.engine.core;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.repository.JdbcActivityRepository;
import com.station8.engine.repository.JdbcLineDefinitionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * DagInterpreter 통합 테스트 — H2 + 실제 schema-h2.sql 적용.
 * 시나리오: 선형 / fan-out / fan-in / 복합(다이아몬드)
 */
class DagInterpreterTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static DagInterpreter interpreter;
    private static JdbcActivityRepository activityRepo;
    private static JdbcLineDefinitionRepository defRepo;

    private static final DbDialect H2_DIALECT = new DbDialect() {
        @Override public String limit(int limit) { return " FETCH FIRST " + limit + " ROWS ONLY"; }
        @Override public String currentTimestamp() { return "CURRENT_TIMESTAMP"; }
    };

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:dag_interp_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("sql/schema-h2.sql"));
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        activityRepo = new JdbcActivityRepository(jdbcTemplate, H2_DIALECT);
        defRepo = new JdbcLineDefinitionRepository(jdbcTemplate);

        DagValidator validator = new DagValidator();
        // 본 인터프리터 테스트는 검증을 우회 (모든 activity 이름이 등록된 것으로 가정)
        LineRegistry stubRegistry = new LineRegistry() {
            @Override public Set<String> getActivityNames() { return Set.of("A", "B", "C", "D"); }
        };
        interpreter = new DagInterpreter(defRepo, activityRepo, validator, stubRegistry);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM H_WF_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_WF_EDGE");
        jdbcTemplate.execute("DELETE FROM U_WF_NODE");
        jdbcTemplate.execute("DELETE FROM U_WF_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_WF_DEFINITION");
    }

    @Test
    void linearChain_AThenBThenC() {
        // 정의: A → B → C (선형)
        String defId = "def-linear";
        insertDefinition(defId, "Linear");
        insertNode(defId, "n-a", "A");
        insertNode(defId, "n-b", "B");
        insertNode(defId, "n-c", "C");
        insertEdge(defId, "n-a", "n-b");
        insertEdge(defId, "n-b", "n-c");

        String instanceId = "inst-linear";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        assertEquals("PENDING", statusOf(instanceId, "n-a"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-b"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));

        // A 완료 → B 활성화
        markCompleted(instanceId, "n-a");
        interpreter.onNodeCompleted(instanceId, "n-a");
        assertEquals("PENDING", statusOf(instanceId, "n-b"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));

        // B 완료 → C 활성화
        markCompleted(instanceId, "n-b");
        interpreter.onNodeCompleted(instanceId, "n-b");
        assertEquals("PENDING", statusOf(instanceId, "n-c"));
    }

    @Test
    void fanOut_AThenBAndC() {
        // 정의: A → B, A → C
        String defId = "def-fanout";
        insertDefinition(defId, "FanOut");
        insertNode(defId, "n-a", "A");
        insertNode(defId, "n-b", "B");
        insertNode(defId, "n-c", "C");
        insertEdge(defId, "n-a", "n-b");
        insertEdge(defId, "n-a", "n-c");

        String instanceId = "inst-fanout";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        assertEquals("PENDING", statusOf(instanceId, "n-a"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-b"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));

        markCompleted(instanceId, "n-a");
        interpreter.onNodeCompleted(instanceId, "n-a");

        // B와 C가 동시에 PENDING으로 전이되어야 함 (fan-out)
        assertEquals("PENDING", statusOf(instanceId, "n-b"));
        assertEquals("PENDING", statusOf(instanceId, "n-c"));
    }

    @Test
    void fanIn_AandBThenC() {
        // 정의: A → C, B → C (fan-in)
        String defId = "def-fanin";
        insertDefinition(defId, "FanIn");
        insertNode(defId, "n-a", "A");
        insertNode(defId, "n-b", "B");
        insertNode(defId, "n-c", "C");
        insertEdge(defId, "n-a", "n-c");
        insertEdge(defId, "n-b", "n-c");

        String instanceId = "inst-fanin";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        // 시작 역 2개 (A, B), C는 대기
        assertEquals("PENDING", statusOf(instanceId, "n-a"));
        assertEquals("PENDING", statusOf(instanceId, "n-b"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));

        // A만 완료 → C 아직 대기 (B 미완)
        markCompleted(instanceId, "n-a");
        interpreter.onNodeCompleted(instanceId, "n-a");
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));

        // B 완료 → 비로소 C 활성화
        markCompleted(instanceId, "n-b");
        interpreter.onNodeCompleted(instanceId, "n-b");
        assertEquals("PENDING", statusOf(instanceId, "n-c"));
    }

    @Test
    void diamond_AThenBandC_thenD() {
        // 다이아몬드: A → B, A → C, B → D, C → D
        String defId = "def-diamond";
        insertDefinition(defId, "Diamond");
        insertNode(defId, "n-a", "A");
        insertNode(defId, "n-b", "B");
        insertNode(defId, "n-c", "C");
        insertNode(defId, "n-d", "D");
        insertEdge(defId, "n-a", "n-b");
        insertEdge(defId, "n-a", "n-c");
        insertEdge(defId, "n-b", "n-d");
        insertEdge(defId, "n-c", "n-d");

        String instanceId = "inst-diamond";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        // A만 PENDING
        assertEquals("PENDING", statusOf(instanceId, "n-a"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-b"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-d"));

        markCompleted(instanceId, "n-a");
        interpreter.onNodeCompleted(instanceId, "n-a");
        // fan-out: B, C 활성화
        assertEquals("PENDING", statusOf(instanceId, "n-b"));
        assertEquals("PENDING", statusOf(instanceId, "n-c"));
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-d"));

        // B만 완료 — D는 C 대기 중
        markCompleted(instanceId, "n-b");
        interpreter.onNodeCompleted(instanceId, "n-b");
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-d"));

        // C 완료 — fan-in 만족 → D 활성화
        markCompleted(instanceId, "n-c");
        interpreter.onNodeCompleted(instanceId, "n-c");
        assertEquals("PENDING", statusOf(instanceId, "n-d"));
    }

    @Test
    void terminalNodeCompletion_isNoOp() {
        // 단일 역 정의: 종단 역 완료 시 후행 없음
        String defId = "def-single";
        insertDefinition(defId, "Single");
        insertNode(defId, "n-only", "A");

        String instanceId = "inst-single";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        assertEquals("PENDING", statusOf(instanceId, "n-only"));

        markCompleted(instanceId, "n-only");
        // 후행 없음 — 예외 없이 처리되어야 함
        interpreter.onNodeCompleted(instanceId, "n-only");
        assertEquals("COMPLETED", statusOf(instanceId, "n-only"));
    }

    // === Helpers ===

    private void insertDefinition(String id, String name) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, 1, 'Y', 'Y', 'Y', 'N')
                """, id, name);
    }

    private void insertNode(String defId, String nodeId, String activityNm) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_NODE (ID, DEFINITION_ID, ACTIVITY_NM, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, 'Y', 'Y', 'N')
                """, nodeId, defId, activityNm);
    }

    private void insertEdge(String defId, String fromNodeId, String toNodeId) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_EDGE (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, 'Y', 'Y', 'N')
                """, "edge-" + fromNodeId + "-" + toNodeId, defId, fromNodeId, toNodeId);
    }

    private void insertInstance(String instanceId) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, 'TestDag', 'RUNNING', 'Y', 'Y', 'N')
                """, instanceId);
    }

    private String statusOf(String instanceId, String nodeId) {
        ActivityExecution exec = activityRepo.findByInstanceAndNode(instanceId, nodeId);
        assertNotNull(exec, "Execution not found: instance=" + instanceId + ", node=" + nodeId);
        return exec.statusSt();
    }

    private void markCompleted(String instanceId, String nodeId) {
        jdbcTemplate.update(
                "UPDATE H_WF_ACTIVITY_EXECUTION SET STATUS_ST = 'COMPLETED' WHERE INSTANCE_ID = ? AND NODE_ID = ?",
                instanceId, nodeId);
    }
}
