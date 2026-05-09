package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAG 정의 API + 서비스 통합 테스트.
 * 시나리오:
 *  - 정상 등록 → 조회 → 즉시 실행 → 시작 역 PENDING 확인
 *  - 검증 실패 (사이클) → 예외
 *  - PUT 교체 → 역/엣지 갱신 확인
 *  - DELETE 후 조회 실패
 */
@SpringBootTest(classes = Application.class)
class LineDefinitionApiTest {

    @Autowired LineDefinitionService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ActivityRepository activityRepository;
    @Autowired JsonUtil jsonUtil;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"),
                new ClassPathResource("sql/migration-test-data.sql")
        );
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        // 테스트 격리
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    @Test
    void create_get_run_E2E() {
        // 정의: ValidateOrder → RunBatch (RUN_BATCH_JOB은 SpringBatchActivityAdapter가 등록)
        DagDefinitionRequest req = new DagDefinitionRequest(
                "E2EFlow",
                "정의 등록 → 즉시 실행까지 검증",
                List.of(
                        new DagDefinitionRequest.NodeDef("n-1", "FirstStep", "MIGRATION_WRITE",
                                jsonUtil.toJson(Map.of("id", "9", "content", "noop")), 100, 100, null),
                        new DagDefinitionRequest.NodeDef("n-2", "BatchStep", "RUN_BATCH_JOB",
                                jsonUtil.toJson(Map.of("jobName", "sampleBatchJob",
                                        "params", Map.of("fileDate", "2026-05-07"))),
                                300, 100, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef("e-1", "n-1", "n-2", null))
        );

        String defId = service.createDefinition(req);
        assertNotNull(defId);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        assertEquals("E2EFlow", fetched.definitionNm());
        assertEquals(1, fetched.versionNo());
        assertEquals(2, fetched.nodes().size());
        assertEquals(1, fetched.edges().size());

        String instanceId = service.runDefinition(defId, null);
        assertNotNull(instanceId);

        // 즉시 실행 직후: 시작 역은 PENDING, 후행은 WAITING_DEPENDENCIES
        List<ActivityExecution> activities = activityRepository.findActivitiesByInstanceId(instanceId);
        assertEquals(2, activities.size());
        long pending = activities.stream().filter(a -> "PENDING".equals(a.statusSt())).count();
        long waiting = activities.stream().filter(a -> "WAITING_DEPENDENCIES".equals(a.statusSt())).count();
        assertEquals(1, pending, "시작 역 1개가 PENDING");
        assertEquals(1, waiting, "후행 역 1개가 WAITING_DEPENDENCIES");
    }

    @Test
    void create_with_cycle_throws() {
        // A → B → A 사이클
        DagDefinitionRequest req = new DagDefinitionRequest(
                "CycleFlow", null,
                List.of(
                        new DagDefinitionRequest.NodeDef("c-a", "A", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("c-b", "B", "MIGRATION_WRITE", null, 0, 0, null)
                ),
                List.of(
                        new DagDefinitionRequest.EdgeDef("c-e1", "c-a", "c-b", null),
                        new DagDefinitionRequest.EdgeDef("c-e2", "c-b", "c-a", null)
                )
        );
        Exception ex = assertThrows(Exception.class, () -> service.createDefinition(req));
        assertTrue(ex.getMessage().contains("WF-E305") || ex.getMessage().contains("CYCLE"),
                "사이클 에러코드(WF-E305) 포함 기대: " + ex.getMessage());
    }

    @Test
    void create_with_unknown_activity_throws() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "UnknownActFlow", null,
                List.of(new DagDefinitionRequest.NodeDef("u-1", "X", "NO_SUCH_ACTIVITY", null, 0, 0, null)),
                List.of()
        );
        Exception ex = assertThrows(Exception.class, () -> service.createDefinition(req));
        assertTrue(ex.getMessage().contains("WF-E307") || ex.getMessage().contains("UNKNOWN_ACTIVITY"),
                "미등록 액티비티 에러코드(WF-E307) 포함 기대: " + ex.getMessage());
    }

    @Test
    void replace_swaps_nodes_and_edges() {
        DagDefinitionRequest v1 = new DagDefinitionRequest(
                "ReplaceFlow", "v1",
                List.of(
                        new DagDefinitionRequest.NodeDef("r-a", "A", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("r-b", "B", "MIGRATION_WRITE", null, 0, 0, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef("r-e1", "r-a", "r-b", null))
        );
        String defId = service.createDefinition(v1);

        DagDefinitionRequest v2 = new DagDefinitionRequest(
                "ReplaceFlow", "v2-replaced",
                List.of(
                        new DagDefinitionRequest.NodeDef("r2-a", "A2", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("r2-b", "B2", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("r2-c", "C2", "MIGRATION_WRITE", null, 0, 0, null)
                ),
                List.of(
                        new DagDefinitionRequest.EdgeDef("r2-e1", "r2-a", "r2-b", null),
                        new DagDefinitionRequest.EdgeDef("r2-e2", "r2-b", "r2-c", null)
                )
        );
        service.replaceDefinition(defId, v2);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        assertEquals("v2-replaced", fetched.description());
        assertEquals(3, fetched.nodes().size(), "역 수 v1=2 → v2=3");
        assertEquals(2, fetched.edges().size(), "엣지 수 v1=1 → v2=2");
    }

    @Test
    void delete_then_get_throws() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "DeleteFlow", null,
                List.of(new DagDefinitionRequest.NodeDef("d-a", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        service.deleteDefinition(defId);

        assertThrows(IllegalArgumentException.class, () -> service.getDefinition(defId));
    }

    @Test
    void duplicate_name_creates_new_version() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "VersionedFlow", null,
                List.of(new DagDefinitionRequest.NodeDef("v1-a", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String id1 = service.createDefinition(req);
        String id2 = service.createDefinition(new DagDefinitionRequest(
                "VersionedFlow", null,
                List.of(new DagDefinitionRequest.NodeDef("v2-a", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        ));
        assertNotEquals(id1, id2);
        assertEquals(1, service.getDefinition(id1).versionNo());
        assertEquals(2, service.getDefinition(id2).versionNo());
    }
}
