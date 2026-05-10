package com.station8.app.controller;

import com.station8.app.Application;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * #132 — GET /api/line/instances/{id}/state JSON 엔드포인트 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class InstanceStateEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
    }

    @Test
    void state_runningInstance_returnsTerminalFalseAndIsRunningTrue() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
                VALUES (?, 'PollFlow', 'RUNNING', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId);

        // 활동 1개 — PENDING
        activityRepository.createPending(instanceId, "DOWORK", "{\"k\":1}", null);

        mockMvc.perform(get("/api/line/instances/" + instanceId + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value(instanceId))
                .andExpect(jsonPath("$.instance.statusSt").value("RUNNING"))
                .andExpect(jsonPath("$.instance.running").value(true))
                .andExpect(jsonPath("$.instance.terminal").value(false))
                .andExpect(jsonPath("$.instance.badgeClass").value("warning"))
                .andExpect(jsonPath("$.activities[0].activityName").value("DOWORK"))
                .andExpect(jsonPath("$.activities[0].statusSt").value("PENDING"))
                .andExpect(jsonPath("$.activities[0].dotClass").value("pending"))
                .andExpect(jsonPath("$.statusByNode").exists());
    }

    @Test
    void state_completedInstance_returnsTerminalTrue() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, END_DT, REG_DT)
                VALUES (?, 'DoneFlow', 'COMPLETED', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId);

        mockMvc.perform(get("/api/line/instances/" + instanceId + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.statusSt").value("COMPLETED"))
                .andExpect(jsonPath("$.instance.running").value(false))
                .andExpect(jsonPath("$.instance.terminal").value(true))
                .andExpect(jsonPath("$.instance.badgeClass").value("success"));
    }

    @Test
    void state_unknownInstance_returns404() throws Exception {
        mockMvc.perform(get("/api/line/instances/ghost-id/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void timelinePage_runningInstance_includesAjaxPollScript() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
                VALUES (?, 'PollPage', 'RUNNING', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId);

        mockMvc.perform(get("/line/instance/" + instanceId))
                .andExpect(status().isOk())
                // AJAX 폴링 JS — INSTANCE_ID 상수 + state endpoint 경로 + 폴링 간격
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INSTANCE_ID = '" + instanceId + "'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'/api/line/instances/' + INSTANCE_ID + '/state'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("POLL_INTERVAL_MS = 3000")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("isRunningInitially = true")))
                // 헤더에 live indicator + 토글
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Auto-refresh 3s")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("auto-refresh-toggle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("live-indicator")));
    }

    @Test
    void timelinePage_completedInstance_doesNotShowLiveIndicator() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, END_DT, REG_DT)
                VALUES (?, 'DonePage', 'COMPLETED', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId);

        mockMvc.perform(get("/line/instance/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("isRunningInitially = false")))
                // RUNNING이 아니므로 live-indicator 헤더 블록 자체가 안 렌더
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Auto-refresh 3s"))));
    }
}
