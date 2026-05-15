package com.station8.app.controller;

import com.station8.app.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard "Details" 버튼 → /line/instance/{id} 라우트 회귀 가드.
 *
 * <p>이전엔 존재하지 않는 instance ID로 접근 시 NPE/EmptyResultDataAccessException으로
 * 500이 발생. 본 테스트는 not-found 시나리오를 명시적으로 404로 응답하는지 가드.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class LineMonitoringControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void timeline_unknownInstanceId_returns404() throws Exception {
        mockMvc.perform(get("/line/instance/does-not-exist-anywhere-12345"))
                .andExpect(status().isNotFound());
    }

    @Test
    void timeline_blankPathSegment_doesNotMatch() throws Exception {
        // 빈 path는 라우트 매칭 자체가 안 되어야 — 별도 핸들러로 가지 않게 가드
        mockMvc.perform(get("/line/instance/"))
                .andExpect(status().isNotFound());
    }
}
