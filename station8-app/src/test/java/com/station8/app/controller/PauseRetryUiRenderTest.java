package com.station8.app.controller;

import com.station8.app.Application;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #139 — timeline UI: 인스턴스 상태별 액션 버튼 + activity-level retry 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class PauseRetryUiRenderTest {

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
    void timeline_runningInstance_showsPauseAndTerminate() throws Exception {
        String inst = seedInstance("FlowR", "RUNNING");

        mockMvc.perform(get("/line/instance/" + inst))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/pause")))
                .andExpect(content().string(containsString("⏸ Pause")))
                .andExpect(content().string(containsString("/terminate")))
                .andExpect(content().string(not(containsString("/unpause"))))
                .andExpect(content().string(not(containsString("Resume from failure"))));
    }

    @Test
    void timeline_pausedInstance_showsUnpauseAndTerminate() throws Exception {
        String inst = seedInstance("FlowP", "PAUSED");

        mockMvc.perform(get("/line/instance/" + inst))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/unpause")))
                .andExpect(content().string(containsString("Resume (unpause)")))
                .andExpect(content().string(containsString("/terminate")))
                .andExpect(content().string(not(containsString("⏸ Pause"))));
    }

    @Test
    void timeline_failedInstance_showsResumeFromFailure() throws Exception {
        String inst = seedInstance("FlowF", "FAILED");

        mockMvc.perform(get("/line/instance/" + inst))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/resume")))
                .andExpect(content().string(containsString("Resume from failure")))
                .andExpect(content().string(not(containsString("⏸ Pause"))))
                .andExpect(content().string(not(containsString("/unpause"))));
    }

    @Test
    void timeline_failedActivity_inRunningInstance_showsRetryButton() throws Exception {
        String inst = seedInstance("FlowAR", "RUNNING");
        String execId = seedActivity(inst, "X", "FAILED");

        mockMvc.perform(get("/line/instance/" + inst))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/activity/" + execId + "/retry")))
                .andExpect(content().string(containsString("Retry this activity")));
    }

    @Test
    void timeline_failedActivity_inPausedInstance_hidesRetryButton() throws Exception {
        // 인스턴스 PAUSED면 활동 retry 버튼은 숨김 (사용자 먼저 unpause 필요)
        String inst = seedInstance("FlowAP", "PAUSED");
        seedActivity(inst, "X", "FAILED");

        mockMvc.perform(get("/line/instance/" + inst))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Retry this activity"))));
    }

    private String seedInstance(String workflowName, String status) {
        String id = "inst-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, id, workflowName, status);
        return id;
    }

    private String seedActivity(String instanceId, String activityName, String status) {
        // FK: H_LINE_ACTIVITY_EXECUTION.NODE_ID → U_LINE_STATION.ID
        // 미리 정의 + 스테이션 시드 (테스트용 dummy)
        String defId = "def-" + UUID.randomUUID();
        String nodeId = "n-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL,
                USE_FL, VIEW_FL, DEL_FL, REG_DT)
            VALUES (?, ?, 1, 'Y', 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
            """, defId, "dummy-" + defId.substring(0, 8));
        jdbcTemplate.update("""
            INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM,
                USE_FL, VIEW_FL, DEL_FL, REG_DT)
            VALUES (?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
            """, nodeId, defId, activityName, activityName);

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
