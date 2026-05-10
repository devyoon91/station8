package com.station8.app.controller;

import com.station8.app.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #137 — Dashboard / DLQ 필터 강화 UI 렌더 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class DashboardDlqFiltersRenderTest {

    @Autowired MockMvc mockMvc;

    @Test
    void dashboard_rendersAdvancedFiltersAndSortableHeaders() throws Exception {
        mockMvc.perform(get("/line/dashboard"))
                .andExpect(status().isOk())
                // Advanced filters details (D7)
                .andExpect(content().string(containsString("More filters")))
                // 다중 status 체크박스 4개 (D2)
                .andExpect(content().string(containsString("name=\"statusSt\" value=\"RUNNING\"")))
                .andExpect(content().string(containsString("name=\"statusSt\" value=\"COMPLETED\"")))
                .andExpect(content().string(containsString("name=\"statusSt\" value=\"FAILED\"")))
                .andExpect(content().string(containsString("name=\"statusSt\" value=\"TERMINATED\"")))
                // 날짜 input (D1)
                .andExpect(content().string(containsString("type=\"date\" id=\"startDtFrom\"")))
                .andExpect(content().string(containsString("type=\"date\" id=\"startDtTo\"")))
                // sort hidden + 헤더 링크 (D3)
                .andExpect(content().string(containsString("name=\"sortBy\"")))
                .andExpect(content().string(containsString("name=\"sortDir\"")))
                .andExpect(content().string(containsString("sortBy=START_DT")))
                .andExpect(content().string(containsString("sortBy=END_DT")));
    }

    @Test
    void dashboard_advancedOpenWhenFiltersActive() throws Exception {
        // 날짜 필터를 사용하면 details가 자동으로 펼쳐짐
        mockMvc.perform(get("/line/dashboard")
                        .param("startDtFrom", "2026-05-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<details open")));
    }

    @Test
    void dashboard_multiStatusChecked() throws Exception {
        // 다중 status 파라미터 → 해당 체크박스 selected
        mockMvc.perform(get("/line/dashboard")
                        .param("statusSt", "RUNNING")
                        .param("statusSt", "FAILED"))
                .andExpect(status().isOk())
                // RUNNING과 FAILED 둘 다 checked
                .andExpect(content().string(containsString("value=\"RUNNING\" checked")))
                .andExpect(content().string(containsString("value=\"FAILED\" checked")));
    }

    @Test
    void dashboard_sortHeader_togglesDirection() throws Exception {
        // 현재 START_DT DESC면, 헤더 링크는 START_DT ASC 가리킴
        String body = mockMvc.perform(get("/line/dashboard")
                        .param("sortBy", "START_DT")
                        .param("sortDir", "DESC"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // START_DT 헤더는 ASC로 토글되는 href를 가져야 함
        assert body.contains("sortBy=START_DT&sortDir=ASC");
        // 다른 컬럼(END_DT) 헤더는 DESC로 시작 (D8=b)
        assert body.contains("sortBy=END_DT&sortDir=DESC");
    }

    @Test
    void dlq_rendersAdvancedFiltersAndSortableHeaders() throws Exception {
        mockMvc.perform(get("/line/dlq"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("More filters")))
                // activity / errorMessage input
                .andExpect(content().string(containsString("name=\"activityName\"")))
                .andExpect(content().string(containsString("name=\"errorMessage\"")))
                // 다중 dlqStatusSt 체크박스
                .andExpect(content().string(containsString("name=\"dlqStatusSt\" value=\"NEW\"")))
                .andExpect(content().string(containsString("name=\"dlqStatusSt\" value=\"REQUEUED\"")))
                .andExpect(content().string(containsString("name=\"dlqStatusSt\" value=\"DISCARDED\"")))
                // 날짜 input
                .andExpect(content().string(containsString("name=\"failedAtFrom\"")))
                .andExpect(content().string(containsString("name=\"failedAtTo\"")))
                // sort header
                .andExpect(content().string(containsString("sortBy=FAILED_AT_DT")))
                .andExpect(content().string(containsString("sortBy=ACTIVITY_NAME")));
    }
}
