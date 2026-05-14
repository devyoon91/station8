package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.InstanceQueryFilter;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;

import java.time.LocalDateTime;
import java.util.Collection;
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
    private RunOptionsCodec codec;
    private LineContextFactory factory;

    @BeforeEach
    void setUp() {
        JsonUtil jsonUtil = new JsonUtil();
        repo = new StubActivityRepository();
        codec = new RunOptionsCodec(jsonUtil);
        factory = new LineContextFactory(repo, jsonUtil, codec);
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

    /** 본 테스트에서 사용하는 메서드만 동작하고 나머지는 no-op인 stub. */
    private static class StubActivityRepository implements ActivityRepository {
        LineInstance instance;
        RuntimeException exceptionToThrow;

        @Override
        public LineInstance findInstanceById(String instanceId) {
            if (exceptionToThrow != null) {
                throw exceptionToThrow;
            }
            return instance;
        }

        @Override public List<ActivityExecution> findPendingActivitiesWithLock(int limit) { return List.of(); }
        @Override public void updateStatus(ActivityExecution activityExecution) { }
        @Override public String createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt) { return null; }
        @Override public String createForNode(String instanceId, String nodeId, String activityName, String statusSt, String inputData) { return null; }
        @Override public ActivityExecution findById(String executionId) { return null; }
        @Override public ActivityExecution findByInstanceAndNode(String instanceId, String nodeId) { return null; }
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
}
