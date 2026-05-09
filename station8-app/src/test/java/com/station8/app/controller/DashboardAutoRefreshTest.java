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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #100 — Dashboard ?auto=1 메타 refresh + 버튼 토글 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class DashboardAutoRefreshTest {

    @Autowired MockMvc mockMvc;
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
    void dashboard_default_doesNotEmitMetaRefresh() throws Exception {
        mockMvc.perform(get("/line/dashboard"))
                .andExpect(status().isOk())
                // meta refresh 미포함
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("http-equiv=\"refresh\""))))
                // "Auto-refresh" 진입 버튼 노출
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/line/dashboard?auto=1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "↻ Auto-refresh")));
    }

    @Test
    void dashboard_auto1_emitsMetaRefreshAnd5sIndicator() throws Exception {
        mockMvc.perform(get("/line/dashboard?auto=1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<meta http-equiv=\"refresh\" content=\"5\">")))
                // 활성 표시
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Auto-refresh: 5s")))
                // Stop auto 토글 노출 (단순 dashboard 링크로 복귀)
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "■ Stop auto")));
    }

    @Test
    void dashboard_autoTrue_alsoActivates() throws Exception {
        mockMvc.perform(get("/line/dashboard?auto=true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<meta http-equiv=\"refresh\"")));
    }

    @Test
    void dashboard_autoZero_doesNotActivate() throws Exception {
        mockMvc.perform(get("/line/dashboard?auto=0"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("http-equiv=\"refresh\""))));
    }
}
