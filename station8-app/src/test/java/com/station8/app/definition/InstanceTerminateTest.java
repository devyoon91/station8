package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.core.DagInterpreter;
import com.station8.engine.core.LineExecutor;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * #101 Instance terminate — terminateLine 동작 + DagInterpreter fan-out 차단 + REST/UI 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class InstanceTerminateTest {

    @Autowired LineDefinitionService service;
    @Autowired LineExecutor lineExecutor;
    @Autowired DagInterpreter dagInterpreter;
    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    @Test
    void terminate_marksInstanceAndPendingActivities() {
        String instanceId = createRunningInstanceWithActivities();

        // 시작 안 한 액티비티 2개(WAITING + PENDING) 확인
        List<ActivityExecution> before = activityRepository.findActivitiesByInstanceId(instanceId);
        long pendingOrWaitingBefore = before.stream()
                .filter(a -> "PENDING".equals(a.statusSt()) || "WAITING_DEPENDENCIES".equals(a.statusSt()))
                .count();
        assertThat(pendingOrWaitingBefore).isEqualTo(2);

        // 종료
        lineExecutor.terminateLine(instanceId);

        // 인스턴스 TERMINATED
        LineInstance instance = activityRepository.findInstanceById(instanceId);
        assertThat(instance.statusSt()).isEqualTo("TERMINATED");
        assertThat(instance.endDt()).isNotNull();

        // PENDING/WAITING → TERMINATED, RUNNING/COMPLETED는 그대로
        List<ActivityExecution> after = activityRepository.findActivitiesByInstanceId(instanceId);
        Map<String, Long> byStatus = after.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ActivityExecution::statusSt, java.util.stream.Collectors.counting()));
        assertThat(byStatus.getOrDefault("TERMINATED", 0L)).isEqualTo(2L);
        assertThat(byStatus.getOrDefault("RUNNING", 0L)).isEqualTo(1L);  // 자연 완료에 맡김
    }

    @Test
    void terminate_unknownInstance_throwsIllegalArgument() {
        assertThatThrownBy(() -> lineExecutor.terminateLine("ghost-instance"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인스턴스를 찾을 수 없습니다");
    }

    @Test
    void terminate_alreadyCompletedInstance_throwsIllegalState() {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, 'SomeFlow', 'COMPLETED', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId);

        assertThatThrownBy(() -> lineExecutor.terminateLine(instanceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING 상태가 아니");
    }

    @Test
    void dagInterpreter_blocksFanOut_whenInstanceIsTerminated() {
        String instanceId = createRunningInstanceWithActivities();
        ActivityExecution running = activityRepository.findActivitiesByInstanceId(instanceId).stream()
                .filter(a -> "RUNNING".equals(a.statusSt()))
                .findFirst().orElseThrow();

        // 종료 → RUNNING 액티비티가 늦게 완료되어도 후행 활성화 안 됨
        lineExecutor.terminateLine(instanceId);

        // 워커가 RUNNING 액티비티를 COMPLETED 처리한 시뮬레이션 — 단, fan-out은 차단되어야 함
        dagInterpreter.onNodeCompleted(instanceId, running.nodeId());

        // 후행 노드들이 promote되지 않고 TERMINATED 상태 유지
        List<ActivityExecution> after = activityRepository.findActivitiesByInstanceId(instanceId);
        long pending = after.stream().filter(a -> "PENDING".equals(a.statusSt())).count();
        assertThat(pending).isZero();  // fan-out 차단됨
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void rest_terminate_returns200_andMarksInstance() throws Exception {
        // #140 — 글로벌 ADMIN으로 ACL 우회 (legacy/open 정의는 모두 통과지만, 명시 ADMIN으로 보장)
        String instanceId = createRunningInstanceWithActivities();

        mockMvc.perform(post("/api/line/instances/" + instanceId + "/terminate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.instanceId").value(instanceId));

        assertThat(activityRepository.findInstanceById(instanceId).statusSt()).isEqualTo("TERMINATED");
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void rest_terminate_unknownInstance_returns404() throws Exception {
        // 글로벌 ADMIN이라 ACL 통과 후 존재하지 않는 instance에서 404
        mockMvc.perform(post("/api/line/instances/ghost/terminate").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void rest_terminate_completedInstance_returns409() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, 'F', 'COMPLETED', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId);

        mockMvc.perform(post("/api/line/instances/" + instanceId + "/terminate").with(csrf()))
                .andExpect(status().isConflict());
    }

    /**
     * 검증: timeline 페이지에서 RUNNING 상태일 때만 Terminate 버튼이 노출.
     * 헤더 영역 버튼 가시성에 집중 — 액티비티 0건 (액티비티 행은 별도 테스트 영역).
     */
    @Test
    void timelinePage_runningInstance_rendersTerminateButton() throws Exception {
        String instanceId = seedBareInstance("RunningFlow", "RUNNING");

        mockMvc.perform(get("/line/instance/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/line/instance/" + instanceId + "/terminate")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Terminate")))
                // RUNNING 상태이므로 Resume은 노출 안 됨
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                                "/line/instance/" + instanceId + "/resume"))));
    }

    @Test
    void timelinePage_completedInstance_doesNotRenderTerminateButton() throws Exception {
        String instanceId = seedBareInstance("DoneFlow", "COMPLETED");

        mockMvc.perform(get("/line/instance/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                                "/line/instance/" + instanceId + "/terminate"))));
    }

    @Test
    void timelinePage_failedInstance_rendersResumeButNotTerminate() throws Exception {
        String instanceId = seedBareInstance("FailedFlow", "FAILED");

        mockMvc.perform(get("/line/instance/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/line/instance/" + instanceId + "/resume")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                                "/line/instance/" + instanceId + "/terminate"))));
    }

    /** 액티비티 없는 단순 인스턴스 시드 — UI 헤더 버튼 가시성 검증용. */
    private String seedBareInstance(String workflowName, String statusSt) {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId, workflowName, statusSt);
        return instanceId;
    }

    /**
     * E2E 검증: 실제 워커가 RUNNING 액티비티를 자연 완료한 뒤,
     * DagInterpreter가 인스턴스 TERMINATED를 보고 fan-out을 차단해 후행 PENDING이 생성되지 않음을 확인.
     */
    @Test
    void e2e_terminate_then_runningActivityCompletes_shouldNotPromoteSuccessor() {
        String instanceId = createRunningInstanceWithActivities();

        // Terminate
        lineExecutor.terminateLine(instanceId);

        // RUNNING 액티비티(t-a)의 사후 자연 완료를 시뮬레이션:
        // 워커는 동일하게 ActivityRepository.updateStatus(COMPLETED) + dagInterpreter.onNodeCompleted를 호출
        ActivityExecution running = activityRepository.findByInstanceAndNode(instanceId, "t-a");
        jdbcTemplate.update("""
            UPDATE H_LINE_ACTIVITY_EXECUTION
            SET STATUS_ST = 'COMPLETED', END_DT = CURRENT_TIMESTAMP
            WHERE ID = ?
            """, running.id());
        dagInterpreter.onNodeCompleted(instanceId, "t-a");

        // 모든 후행이 promote되지 않았어야 함 — 인스턴스 TERMINATED라 fan-out 차단
        List<ActivityExecution> after = activityRepository.findActivitiesByInstanceId(instanceId);
        long pendingCount = after.stream().filter(a -> "PENDING".equals(a.statusSt())).count();
        long terminatedCount = after.stream().filter(a -> "TERMINATED".equals(a.statusSt())).count();

        assertThat(pendingCount)
                .as("Terminate 후 RUNNING 자연 완료 시 후행이 PENDING으로 promote되면 안 됨")
                .isZero();
        assertThat(terminatedCount).isEqualTo(2L);  // t-b, t-c 모두 TERMINATED 유지

        // 인스턴스 상태 TERMINATED 유지 (워커가 인스턴스를 RUNNING으로 되돌리지 않음)
        assertThat(activityRepository.findInstanceById(instanceId).statusSt())
                .isEqualTo("TERMINATED");
    }

    /**
     * 1 RUNNING + 1 PENDING + 1 WAITING_DEPENDENCIES 액티비티가 있는 인스턴스 시드.
     */
    private String createRunningInstanceWithActivities() {
        // 정의: A → B → C
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("TerminateFlow")
                .nodes(List.of(
                        new DagDefinitionRequest.NodeDef("t-a", "A", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("t-b", "B", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("t-c", "C", "MIGRATION_WRITE", null, 0, 0, null)
                ))
                .edges(List.of(
                        new DagDefinitionRequest.EdgeDef("t-e1", "t-a", "t-b", null),
                        new DagDefinitionRequest.EdgeDef("t-e2", "t-b", "t-c", null)
                ))
                .build();
        String defId = service.createDefinition(req);

        // 인스턴스 + 액티비티 — A는 RUNNING(워커가 잡았다고 가정), B는 PENDING(promoted), C는 WAITING
        String instanceId = service.runDefinition(defId, null);
        // service.runDefinition이 인터프리터로 startInstance — 시작 노드는 PENDING.
        // 시뮬레이션: A를 RUNNING으로 강제 전이
        ActivityExecution a = activityRepository.findByInstanceAndNode(instanceId, "t-a");
        jdbcTemplate.update("UPDATE H_LINE_ACTIVITY_EXECUTION SET STATUS_ST = 'RUNNING' WHERE ID = ?", a.id());
        // B를 PENDING으로 강제 전이 (promote 시뮬레이션)
        ActivityExecution b = activityRepository.findByInstanceAndNode(instanceId, "t-b");
        jdbcTemplate.update("UPDATE H_LINE_ACTIVITY_EXECUTION SET STATUS_ST = 'PENDING' WHERE ID = ?", b.id());
        return instanceId;
    }
}
