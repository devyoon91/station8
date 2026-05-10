package com.station8.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #175 — Bean Validation 검증.
 *
 * <p>{@code @Valid} 적용 후 검증 실패가 {@code GlobalRestExceptionHandler.handleValidation}을
 * 거쳐 {@code VALIDATION_FAILED} + 필드별 details로 변환되는지 회귀 검증.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(username = "test-admin", roles = "ADMIN")
class BeanValidationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_TAG");
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    @Test
    void definitionCreate_blankName_returnsValidationFailedWithFieldError() throws Exception {
        // definitionNm = "" 빈 문자열 → @NotBlank 위반
        String body = mockMvc.perform(post("/api/line/definitions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionNm": "",
                                  "nodes": [
                                    {"nodeId":"n-1","nodeNm":"A","activityNm":"MIGRATION_WRITE","posX":0,"posY":0}
                                  ],
                                  "edges": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").exists())
                .andExpect(jsonPath("$.details.definitionNm").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("details").get("definitionNm").asText()).contains("definitionNm");
    }

    @Test
    void definitionCreate_emptyNodes_returnsValidationFailed() throws Exception {
        // nodes = [] 빈 배열 → @NotEmpty 위반
        mockMvc.perform(post("/api/line/definitions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionNm": "X",
                                  "nodes": [],
                                  "edges": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.nodes").exists());
    }

    @Test
    void definitionCreate_blankNodeFields_returnsValidationFailed() throws Exception {
        // nodeNm/activityNm 빈 문자열 → 중첩 record @Valid 검증 실행
        mockMvc.perform(post("/api/line/definitions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionNm": "OK",
                                  "nodes": [
                                    {"nodeId":"","nodeNm":"","activityNm":"","posX":0,"posY":0}
                                  ],
                                  "edges": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void definitionCreate_validRequest_succeeds() throws Exception {
        mockMvc.perform(post("/api/line/definitions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionNm": "ValidFlow",
                                  "nodes": [
                                    {"nodeId":"n-1","nodeNm":"A","activityNm":"MIGRATION_WRITE","posX":0,"posY":0}
                                  ],
                                  "edges": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definitionId").exists());
    }

    @Test
    void scheduleCreate_blankCron_returnsValidationFailed() throws Exception {
        mockMvc.perform(post("/api/line/schedules")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"x\",\"cronExpr\":\"\",\"inputData\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.cronExpr").exists());
    }

    @Test
    void scheduleCreate_blankDefinitionId_returnsValidationFailed() throws Exception {
        mockMvc.perform(post("/api/line/schedules")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"\",\"cronExpr\":\"0 0 * * * *\",\"inputData\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.definitionId").exists());
    }

    @Test
    void scheduleUpdateCron_blankExpr_returnsValidationFailed() throws Exception {
        mockMvc.perform(put("/api/line/schedules/some-id")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpr\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.cronExpr").exists());
    }
}
