package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineStation;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.InstanceQueryFilter;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #146 — {@link LineContextFactory} 단위 테스트.
 *
 * <p>인스턴스 조회 / RunOptions CLOB 파싱이 다양한 fallback 경계를 정확히 처리하는지 검증.</p>
 */
class LineContextFactoryTest {

    private StubActivityRepository repo;
    private StubLineDefinitionRepository defRepo;
    private RunOptionsCodec codec;
    private LineContextFactory factory;

    @BeforeEach
    void setUp() {
        JsonUtil jsonUtil = new JsonUtil();
        repo = new StubActivityRepository();
        defRepo = new StubLineDefinitionRepository();
        codec = new RunOptionsCodec(jsonUtil);
        factory = new LineContextFactory(repo, jsonUtil, codec, defRepo);
    }

    @Test
    void create_withInstancePresent_populatesContextAndOptions() {
        // RUN_OPTIONS CLOB에 onFailure=ABORT + runtimeParams + webhook 저장된 인스턴스
        repo.instance = simpleInstance("inst-1", "OrderFlow",
                "{\"onFailure\":\"ABORT\",\"runtimeParams\":{\"region\":\"KR\"},"
                        + "\"notificationWebhookUrl\":\"https://hook\"}");

        ActivityExecution activity = simpleActivity("act-1", "inst-1", "PROCESS", 0);
        LineContextFactory.Bundle bundle = factory.create(activity);

        assertThat(bundle.context().instanceId()).isEqualTo("inst-1");
        assertThat(bundle.context().workflowName()).isEqualTo("OrderFlow");
        assertThat(bundle.context().currentActivityName()).isEqualTo("PROCESS");
        assertThat(bundle.context().attempt()).isEqualTo(1);  // retryCnt(0) + 1
        assertThat(bundle.context().attributes()).containsEntry("executionId", "act-1");

        assertThat(bundle.options().onFailure()).isEqualTo(RunOptions.OnFailure.ABORT);
        assertThat(bundle.options().runtimeParams()).containsEntry("region", "KR");
        assertThat(bundle.options().notificationWebhookUrl()).isEqualTo("https://hook");
    }

    @Test
    void create_runtimeParamsInjectedIntoContext() {
        // factory가 runtime params를 context에 set 하는지 확인
        repo.instance = simpleInstance("inst-2", "TestFlow",
                "{\"runtimeParams\":{\"tier\":\"premium\",\"region\":\"US\"}}");

        ActivityExecution activity = simpleActivity("act-2", "inst-2", "STEP", 0);
        LineContextFactory.Bundle bundle = factory.create(activity);

        // LineContext.runtimeParams() 접근으로 검증
        assertThat(bundle.context().runtimeParams())
                .containsEntry("tier", "premium")
                .containsEntry("region", "US");
    }

    @Test
    void create_withInstanceNotFound_fallsBackToDefaults() {
        // 인스턴스 조회 실패 (EmptyResultDataAccessException) → defaults + workflowName="UNKNOWN"
        repo.exceptionToThrow = new EmptyResultDataAccessException("not found", 1);

        ActivityExecution activity = simpleActivity("act-3", "missing-inst", "X", 0);
        LineContextFactory.Bundle bundle = factory.create(activity);

        assertThat(bundle.context().workflowName()).isEqualTo(LineContextFactory.UNKNOWN_WORKFLOW_NAME);
        assertThat(bundle.options().onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(bundle.options().runtimeParams()).isEmpty();
    }

    @Test
    void create_withInstanceQueryFailure_fallsBackToDefaults() {
        // EmptyResultDataAccessException 외의 예외도 안전 처리
        repo.exceptionToThrow = new RuntimeException("DB down");

        ActivityExecution activity = simpleActivity("act-4", "any-inst", "X", 0);
        LineContextFactory.Bundle bundle = factory.create(activity);

        assertThat(bundle.context().workflowName()).isEqualTo(LineContextFactory.UNKNOWN_WORKFLOW_NAME);
        assertThat(bundle.options()).isEqualTo(RunOptions.defaults());
    }

    @Test
    void create_withInstanceButNullRunOptions_returnsDefaults() {
        // 인스턴스는 있지만 RUN_OPTIONS=null → codec이 defaults() 반환
        repo.instance = simpleInstance("inst-5", "EmptyOpts", null);

        ActivityExecution activity = simpleActivity("act-5", "inst-5", "PROCESS", 2);
        LineContextFactory.Bundle bundle = factory.create(activity);

        assertThat(bundle.context().workflowName()).isEqualTo("EmptyOpts");
        assertThat(bundle.context().attempt()).isEqualTo(3);  // retryCnt(2) + 1
        assertThat(bundle.options()).isEqualTo(RunOptions.defaults());
    }

    @Test
    void create_withMalformedRunOptions_safeFallback() {
        // 잘못된 JSON CLOB → codec이 defaults() 반환 (운영 멈춤 방지)
        repo.instance = simpleInstance("inst-6", "MalformedFlow", "{not valid json");

        ActivityExecution activity = simpleActivity("act-6", "inst-6", "X", 0);
        LineContextFactory.Bundle bundle = factory.create(activity);

        assertThat(bundle.options()).isEqualTo(RunOptions.defaults());
    }

    // ---- #267 — $prev (previousOutput) wiring ----

    @Test
    void create_linearMode_nodeIdNull_previousOutputNull() {
        // legacy/linear 모드 (nodeId=null) → predecessor 조회 자체 안 함, $prev null
        repo.instance = simpleInstance("inst-prev-1", "Flow", null);
        ActivityExecution activity = simpleActivity("act-prev-1", "inst-prev-1", "X", 0);
        // nodeId=null (simpleActivity default)

        LineContextFactory.Bundle bundle = factory.create(activity);

        assertThat(bundle.context().previousOutput()).isEmpty();
    }

    @Test
    void create_dagStartNode_noIncomingEdges_previousOutputNull() {
        // DAG start 노드 (incoming 0건) → $prev null
        repo.instance = simpleInstance("inst-prev-2", "Flow", null);
        defRepo.incomingByNode.put("start-node", List.of());

        ActivityExecution activity = simpleActivityWithNode("act-prev-2", "inst-prev-2", "start-node", "START", 0);
        LineContextFactory.Bundle bundle = factory.create(activity);

        assertThat(bundle.context().previousOutput()).isEmpty();
    }

    @Test
    void create_dagSinglePredecessor_loadsPreviousOutput() {
        // 단일 predecessor → $prev = predecessor의 outputData
        repo.instance = simpleInstance("inst-prev-3", "Flow", null);
        defRepo.incomingByNode.put("node-B", List.of(simpleEdge("node-A", "node-B")));
        repo.activitiesByNode.put("node-A",
                simpleActivityWithNodeAndOutput("act-A", "inst-prev-3", "node-A", "STEP_A",
                        "{\"orderId\":42}"));

        ActivityExecution current = simpleActivityWithNode("act-B", "inst-prev-3", "node-B", "STEP_B", 0);
        LineContextFactory.Bundle bundle = factory.create(current);

        assertThat(bundle.context().previousOutput()).contains("{\"orderId\":42}");
    }

    @Test
    void create_dagFanIn_multiplePredecessors_returnsNull() {
        // fan-in (2개 이상 predecessor) → 모호하므로 null. 사용자는 $ctx.input 사용
        repo.instance = simpleInstance("inst-prev-4", "Flow", null);
        defRepo.incomingByNode.put("merge-node", List.of(
                simpleEdge("a", "merge-node"),
                simpleEdge("b", "merge-node")
        ));

        ActivityExecution current = simpleActivityWithNode("act-merge", "inst-prev-4", "merge-node", "MERGE", 0);
        LineContextFactory.Bundle bundle = factory.create(current);

        assertThat(bundle.context().previousOutput()).isEmpty();
    }

    @Test
    void create_dagPredecessorNotFound_returnsNull() {
        // edge는 있는데 predecessor activity가 아직 안 만들어진 엣지 케이스 → null
        repo.instance = simpleInstance("inst-prev-5", "Flow", null);
        defRepo.incomingByNode.put("node-Y", List.of(simpleEdge("node-X", "node-Y")));
        // repo.activitiesByNode에 node-X 없음

        ActivityExecution current = simpleActivityWithNode("act-Y", "inst-prev-5", "node-Y", "STEP_Y", 0);
        LineContextFactory.Bundle bundle = factory.create(current);

        assertThat(bundle.context().previousOutput()).isEmpty();
    }

    @Test
    void create_definitionRepoThrows_safeFallbackNull() {
        // findIncomingEdges가 예외를 던져도 활동 실행은 안전 — null로 격하
        repo.instance = simpleInstance("inst-prev-6", "Flow", null);
        defRepo.exceptionToThrow = new RuntimeException("DB down");

        ActivityExecution current = simpleActivityWithNode("act-Z", "inst-prev-6", "node-Z", "STEP_Z", 0);
        LineContextFactory.Bundle bundle = factory.create(current);

        assertThat(bundle.context().previousOutput()).isEmpty();
    }

    private static LineInstance simpleInstance(String id, String workflowName, String runOptionsJson) {
        return new LineInstance(
                id, workflowName, "RUNNING",
                null, null, null,
                runOptionsJson,
                LocalDateTime.now(), null,
                "Y", "Y", "N",
                LocalDateTime.now(), "test",
                null, null
        );
    }

    private static ActivityExecution simpleActivity(String id, String instanceId, String activityName, int retryCnt) {
        return new ActivityExecution(
                id, instanceId, null, activityName,
                "PENDING", "input-data", null, null, null,
                retryCnt, null,
                null, null,
                "Y", "Y", "N",
                LocalDateTime.now(), "test", null, null
        );
    }

    private static ActivityExecution simpleActivityWithNode(String id, String instanceId, String nodeId,
                                                            String activityName, int retryCnt) {
        return new ActivityExecution(
                id, instanceId, nodeId, activityName,
                "PENDING", "input-data", null, null, null,
                retryCnt, null,
                null, null,
                "Y", "Y", "N",
                LocalDateTime.now(), "test", null, null
        );
    }

    private static ActivityExecution simpleActivityWithNodeAndOutput(String id, String instanceId, String nodeId,
                                                                     String activityName, String outputData) {
        return new ActivityExecution(
                id, instanceId, nodeId, activityName,
                "COMPLETED", "input-data", outputData, null, null,
                0, null,
                null, null,
                "Y", "Y", "N",
                LocalDateTime.now(), "test", null, null
        );
    }

    private static LineTrack simpleEdge(String fromNodeId, String toNodeId) {
        return new LineTrack(
                "edge-" + fromNodeId + "-" + toNodeId,
                "def-1", fromNodeId, toNodeId, null,
                "Y", "Y", "N",
                LocalDateTime.now(), "test", null, null
        );
    }

    /** 본 테스트에서 사용하는 메서드만 동작하고 나머지는 no-op인 stub. */
    private static class StubActivityRepository implements ActivityRepository {
        LineInstance instance;
        RuntimeException exceptionToThrow;
        /** #267 — predecessor activity lookup: nodeId → activity. */
        Map<String, ActivityExecution> activitiesByNode = new LinkedHashMap<>();

        @Override
        public LineInstance findInstanceById(String instanceId) {
            if (exceptionToThrow != null) {
                throw exceptionToThrow;
            }
            return instance;
        }

        @Override
        public ActivityExecution findByInstanceAndNode(String instanceId, String nodeId) {
            return activitiesByNode.get(nodeId);
        }

        @Override public List<ActivityExecution> findPendingActivitiesWithLock(int limit) { return List.of(); }
        @Override public void updateStatus(ActivityExecution activityExecution) { }
        @Override public String createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt) { return null; }
        @Override public String createForNode(String instanceId, String nodeId, String activityName, String statusSt, String inputData) { return null; }
        @Override public ActivityExecution findById(String executionId) { return null; }
        @Override public void promoteToPending(String executionId) { }
        @Override public List<LineInstance> findAllInstances() { return List.of(); }
        @Override public List<LineInstance> findInstancesPage(InstanceQueryFilter filter, int offset, int limit) { return List.of(); }
        @Override public long countInstances(InstanceQueryFilter filter) { return 0L; }
        @Override public Map<String, Long> countInstancesByStatus() { return Map.of(); }
        @Override public List<ActivityExecution> findActivitiesByInstanceId(String instanceId) { return List.of(); }
        @Override public void resetToPending(String executionId) { }
        @Override public int bulkUpdateNotStartedStatuses(String instanceId, String toStatus) { return 0; }
        @Override public void revertGateBlocked(String executionId, LocalDateTime nextRetryDt) { }
        @Override public boolean isNodeCompleted(String instanceId, String nodeId) { return false; }
        @Override public boolean isAnyNodeStarted(String instanceId, Collection<String> nodeIds) { return false; }
    }

    /**
     * #267 — {@link LineDefinitionRepository} stub. {@code findIncomingEdges}만 사용,
     * 나머지는 호출되면 {@link UnsupportedOperationException}.
     */
    private static class StubLineDefinitionRepository implements LineDefinitionRepository {
        Map<String, List<LineTrack>> incomingByNode = new LinkedHashMap<>();
        RuntimeException exceptionToThrow;

        @Override
        public List<LineTrack> findIncomingEdges(String toNodeId) {
            if (exceptionToThrow != null) throw exceptionToThrow;
            return incomingByNode.getOrDefault(toNodeId, new ArrayList<>());
        }

        @Override public LineDefinition findDefinitionById(String definitionId) { throw nope(); }
        @Override public List<LineDefinition> findAllActiveDefinitions() { throw nope(); }
        @Override public List<LineDefinition> findActiveDefinitionsPage(int offset, int limit) { throw nope(); }
        @Override public long countActiveDefinitions() { throw nope(); }
        @Override public String findDefinitionIdByNodeId(String nodeId) { throw nope(); }
        @Override public LineStation findStationById(String stationId) { throw nope(); }
        @Override public List<LineStation> findNodesByDefinition(String definitionId) { throw nope(); }
        @Override public List<LineTrack> findEdgesByDefinition(String definitionId) { throw nope(); }
        @Override public List<LineTrack> findOutgoingEdges(String fromNodeId) { throw nope(); }
        @Override public List<LineStation> findStartNodes(String definitionId) { throw nope(); }
        @Override public LineDefinition findActiveDefinitionByName(String workflowName) { throw nope(); }
        @Override public void insertDefinition(LineDefinition definition) { throw nope(); }
        @Override public void updateDefinitionMeta(String definitionId, String description, String activeFl) { throw nope(); }
        @Override public void updateDefinitionSla(String definitionId, Long slaSeconds, String slaAction) { throw nope(); }
        @Override public void updateDefinitionConcurrency(String definitionId, String concurrencyPolicy) { throw nope(); }
        @Override public void insertTag(String definitionId, String tag, String regId) { throw nope(); }
        @Override public void deleteTagsByDefinition(String definitionId) { throw nope(); }
        @Override public List<String> findTagsForDefinition(String definitionId) { throw nope(); }
        @Override public Map<String, List<String>> findTagsForDefinitions(Collection<String> definitionIds) { throw nope(); }
        @Override public List<TagCount> findAllTagsWithCount() { throw nope(); }
        @Override public List<String> findDefinitionIdsByTag(String tag) { throw nope(); }
        @Override public void softDeleteDefinition(String definitionId) { throw nope(); }
        @Override public void insertNode(LineStation node) { throw nope(); }
        @Override public void softDeleteNodesByDefinition(String definitionId) { throw nope(); }
        @Override public void insertEdge(LineTrack edge) { throw nope(); }
        @Override public void softDeleteEdgesByDefinition(String definitionId) { throw nope(); }
        @Override public int findMaxVersionByName(String definitionNm) { throw nope(); }

        private static UnsupportedOperationException nope() {
            return new UnsupportedOperationException("not used in LineContextFactoryTest");
        }
    }
}
