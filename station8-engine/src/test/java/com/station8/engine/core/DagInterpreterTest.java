package com.station8.engine.core;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.ActivityExecution;
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
        @Override public String offsetLimit(int offset, int limit) {
            return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }
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
        defRepo = new JdbcLineDefinitionRepository(jdbcTemplate, H2_DIALECT);

        EdgeConditionEvaluator conditionEvaluator = new EdgeConditionEvaluator(new JsonUtil());
        DagValidator validator = new DagValidator(conditionEvaluator);
        // 본 인터프리터 테스트는 검증을 우회 (모든 activity 이름이 등록된 것으로 가정)
        LineRegistry stubRegistry = new LineRegistry() {
            @Override public Set<String> getActivityNames() { return Set.of("A", "B", "C", "D"); }
        };
        // 조건 0건 케이스에서 호출됨 — 본 테스트는 conditionExpr 없는 그래프만 다루므로 no-op 스텁 충분
        LineExecutor noOpExecutor = new LineExecutor() {
            @Override public String startLine(String workflowName, Object input) { throw new UnsupportedOperationException(); }
            @Override public void resumeLine(String instanceId) { throw new UnsupportedOperationException(); }
            @Override public void terminateLine(String instanceId) { throw new UnsupportedOperationException(); }
            @Override public void pauseLine(String instanceId) { throw new UnsupportedOperationException(); }
            @Override public void unpauseLine(String instanceId) { throw new UnsupportedOperationException(); }
            @Override public void retryActivity(String activityExecutionId) { throw new UnsupportedOperationException(); }
            @Override public void terminateLineWithReason(String instanceId, String reason) { /* no-op */ }
            @Override public void failLine(String instanceId, String reason) { /* no-op */ }
        };
        interpreter = new DagInterpreter(defRepo, activityRepo, validator, stubRegistry,
                conditionEvaluator, noOpExecutor, new JsonUtil());
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
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

    // === M22 item-level streaming (#369) ===

    @Test
    void itemStreaming_fanOut_materializesItemRowsPerElement() {
        // A → B(FAN_OUT) → C(COLLECT)
        String defId = "def-fanout-items";
        insertDefinition(defId, "FanOutItems");
        insertNode(defId, "n-a", "A");
        insertNodeWithMode(defId, "n-b", "B", "FAN_OUT");
        insertNodeWithMode(defId, "n-c", "C", "COLLECT");
        insertEdge(defId, "n-a", "n-b");
        insertEdge(defId, "n-b", "n-c");

        String instanceId = "inst-fanout-items";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        // A가 배열 출력으로 완료 → B가 원소당 3행으로 materialize
        markCompletedWithOutput(instanceId, "n-a", "[10,20,30]");
        interpreter.onNodeCompleted(instanceId, "n-a");

        List<ActivityExecution> bRows = activityRepo.findAllByInstanceAndNode(instanceId, "n-b");
        assertEquals(3, bRows.size(), "FAN_OUT은 원소당 1행을 만들어야 함");
        assertEquals(Set.of(0, 1, 2), bRows.stream().map(ActivityExecution::itemIndex).collect(java.util.stream.Collectors.toSet()));
        bRows.forEach(r -> assertEquals("PENDING", r.statusSt(), "모든 item 행은 PENDING(병렬)"));
        // C는 아직 대기 (B 레인 미완)
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));
    }

    @Test
    void itemStreaming_collect_waitsForAllItemsThenPromotesOnce() {
        String defId = "def-collect";
        insertDefinition(defId, "Collect");
        insertNode(defId, "n-a", "A");
        insertNodeWithMode(defId, "n-b", "B", "FAN_OUT");
        insertNodeWithMode(defId, "n-c", "C", "COLLECT");
        insertEdge(defId, "n-a", "n-b");
        insertEdge(defId, "n-b", "n-c");

        String instanceId = "inst-collect";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        markCompletedWithOutput(instanceId, "n-a", "[1,2]");
        interpreter.onNodeCompleted(instanceId, "n-a");
        assertEquals(2, activityRepo.findAllByInstanceAndNode(instanceId, "n-b").size());

        // B의 item 한 개만 완료 → C는 여전히 대기
        completeOneItemRow(instanceId, "n-b", 0);
        interpreter.onNodeCompleted(instanceId, "n-b");
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"));

        // 나머지 item 완료 → 모든 레인 완료 → C 1회 promote
        completeOneItemRow(instanceId, "n-b", 1);
        interpreter.onNodeCompleted(instanceId, "n-b");
        assertEquals("PENDING", statusOf(instanceId, "n-c"));
        assertEquals(1, activityRepo.findAllByInstanceAndNode(instanceId, "n-c").size(), "COLLECT는 1회만 실행");
    }

    @Test
    void itemStreaming_noneNode_doesNotFanOutOnArrayOutput() {
        // opt-in 증명: B가 NONE이면 A의 배열 출력에도 fan-out 안 함 (기존 동작)
        String defId = "def-none-array";
        insertDefinition(defId, "NoneArray");
        insertNode(defId, "n-a", "A");
        insertNode(defId, "n-b", "B");   // STREAM_MODE 기본 NONE
        insertEdge(defId, "n-a", "n-b");

        String instanceId = "inst-none-array";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        markCompletedWithOutput(instanceId, "n-a", "[1,2,3]");
        interpreter.onNodeCompleted(instanceId, "n-a");

        List<ActivityExecution> bRows = activityRepo.findAllByInstanceAndNode(instanceId, "n-b");
        assertEquals(1, bRows.size(), "NONE 노드는 배열이어도 단일 행 (fan-out 안 함)");
        assertEquals("PENDING", bRows.get(0).statusSt());
    }

    @Test
    void itemStreaming_fanOut_nonArrayOutput_degeneratesToSingle() {
        String defId = "def-degenerate";
        insertDefinition(defId, "Degenerate");
        insertNode(defId, "n-a", "A");
        insertNodeWithMode(defId, "n-b", "B", "FAN_OUT");
        insertEdge(defId, "n-a", "n-b");

        String instanceId = "inst-degenerate";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);

        // 단일 객체 출력 → length-1 degenerate
        markCompletedWithOutput(instanceId, "n-a", "{\"id\":1}");
        interpreter.onNodeCompleted(instanceId, "n-a");

        List<ActivityExecution> bRows = activityRepo.findAllByInstanceAndNode(instanceId, "n-b");
        assertEquals(1, bRows.size());
        assertEquals("PENDING", bRows.get(0).statusSt());
    }

    @Test
    void itemStreaming_partialFailure_retryCompletesLane_collectProceeds() {
        // B(FAN_OUT) 2 items. lane 0 = FAILED 후 retry COMPLETED, lane 1 = COMPLETED → C promote.
        String defId = "def-partial-retry";
        insertDefinition(defId, "PartialRetry");
        insertNode(defId, "n-a", "A");
        insertNodeWithMode(defId, "n-b", "B", "FAN_OUT");
        insertNodeWithMode(defId, "n-c", "C", "COLLECT");
        insertEdge(defId, "n-a", "n-b");
        insertEdge(defId, "n-b", "n-c");

        String instanceId = "inst-partial-retry";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);
        markCompletedWithOutput(instanceId, "n-a", "[1,2]");
        interpreter.onNodeCompleted(instanceId, "n-a");

        // lane 0: 원래 행 FAILED + retry 행 COMPLETED (같은 itemIndex 0)
        failItemRow(instanceId, "n-b", 0);
        insertItemRow(instanceId, "n-b", "B", "COMPLETED", 0);
        // lane 1: COMPLETED
        completeOneItemRow(instanceId, "n-b", 1);

        interpreter.onNodeCompleted(instanceId, "n-b");
        // 레인별로 COMPLETED가 있고 in-flight 없음 → fan-in 만족
        assertEquals("PENDING", statusOf(instanceId, "n-c"));
    }

    @Test
    void itemStreaming_permanentItemFailure_blocksCollect() {
        // lane 1이 영구 실패(FAILED만, COMPLETED 없음) → C는 promote 안 됨.
        String defId = "def-perm-fail";
        insertDefinition(defId, "PermFail");
        insertNode(defId, "n-a", "A");
        insertNodeWithMode(defId, "n-b", "B", "FAN_OUT");
        insertNodeWithMode(defId, "n-c", "C", "COLLECT");
        insertEdge(defId, "n-a", "n-b");
        insertEdge(defId, "n-b", "n-c");

        String instanceId = "inst-perm-fail";
        insertInstance(instanceId);
        interpreter.startInstance(defId, instanceId, null);
        markCompletedWithOutput(instanceId, "n-a", "[1,2]");
        interpreter.onNodeCompleted(instanceId, "n-a");

        completeOneItemRow(instanceId, "n-b", 0);  // lane 0 OK
        failItemRow(instanceId, "n-b", 1);          // lane 1 영구 FAILED

        interpreter.onNodeCompleted(instanceId, "n-b");
        assertEquals("WAITING_DEPENDENCIES", statusOf(instanceId, "n-c"),
                "영구 실패 레인이 있으면 collect는 대기 (부분 실패 정책 v1)");
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
                INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, DEL_FL)
                VALUES (?, ?, 1, 'Y', 'N')
                """, id, name);
    }

    private void insertNode(String defId, String nodeId, String activityNm) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, ACTIVITY_NM, DEL_FL)
                VALUES (?, ?, ?, 'N')
                """, nodeId, defId, activityNm);
    }

    private void insertEdge(String defId, String fromNodeId, String toNodeId) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_TRACK (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, DEL_FL)
                VALUES (?, ?, ?, ?, 'N')
                """, "edge-" + fromNodeId + "-" + toNodeId, defId, fromNodeId, toNodeId);
    }

    private void insertInstance(String instanceId) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, DEL_FL)
                VALUES (?, 'TestDag', 'RUNNING', 'N')
                """, instanceId);
    }

    private String statusOf(String instanceId, String nodeId) {
        ActivityExecution exec = activityRepo.findByInstanceAndNode(instanceId, nodeId);
        assertNotNull(exec, "Execution not found: instance=" + instanceId + ", node=" + nodeId);
        return exec.statusSt();
    }

    private void markCompleted(String instanceId, String nodeId) {
        jdbcTemplate.update(
                "UPDATE H_LINE_ACTIVITY_EXECUTION SET STATUS_ST = 'COMPLETED' WHERE INSTANCE_ID = ? AND NODE_ID = ?",
                instanceId, nodeId);
    }

    private void insertNodeWithMode(String defId, String nodeId, String activityNm, String streamMode) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, ACTIVITY_NM, STREAM_MODE, DEL_FL)
                VALUES (?, ?, ?, ?, 'N')
                """, nodeId, defId, activityNm, streamMode);
    }

    private void markCompletedWithOutput(String instanceId, String nodeId, String outputJson) {
        jdbcTemplate.update(
                "UPDATE H_LINE_ACTIVITY_EXECUTION SET STATUS_ST = 'COMPLETED', OUTPUT_DATA = ? WHERE INSTANCE_ID = ? AND NODE_ID = ?",
                outputJson, instanceId, nodeId);
    }

    private void completeOneItemRow(String instanceId, String nodeId, int itemIndex) {
        jdbcTemplate.update(
                "UPDATE H_LINE_ACTIVITY_EXECUTION SET STATUS_ST = 'COMPLETED' WHERE INSTANCE_ID = ? AND NODE_ID = ? AND ITEM_INDEX = ?",
                instanceId, nodeId, itemIndex);
    }

    private void failItemRow(String instanceId, String nodeId, int itemIndex) {
        jdbcTemplate.update(
                "UPDATE H_LINE_ACTIVITY_EXECUTION SET STATUS_ST = 'FAILED' WHERE INSTANCE_ID = ? AND NODE_ID = ? AND ITEM_INDEX = ?",
                instanceId, nodeId, itemIndex);
    }

    /** retry/추가 item 행을 임의 상태로 삽입 (부분 실패 시나리오 구성용). */
    private void insertItemRow(String instanceId, String nodeId, String activityNm, String status, int itemIndex) {
        jdbcTemplate.update("""
                INSERT INTO H_LINE_ACTIVITY_EXECUTION (ID, INSTANCE_ID, NODE_ID, ITEM_INDEX, ACTIVITY_NAME, STATUS_ST, DEL_FL, REG_DT)
                VALUES (?, ?, ?, ?, ?, ?, 'N', CURRENT_TIMESTAMP)
                """, java.util.UUID.randomUUID().toString(), instanceId, nodeId, itemIndex, activityNm, status);
    }
}
