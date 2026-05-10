package com.station8.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.Application;
import com.station8.app.definition.DagDefinitionRequest;
import com.station8.app.definition.LineDefinitionService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #174 — 전역 ExceptionHandler 동작 + 응답 포맷 검증.
 *
 * <p>이전 controller-level 핸들러가 처리하던 예외가 글로벌 핸들러로 통합된 후에도
 * 동일한 status + 메시지 노출되는지 회귀 검증.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(username = "test-admin", roles = "ADMIN")
class GlobalRestExceptionHandlerTest {

    @Autowired MockMvc mockMvc;
    @Autowired LineDefinitionService service;
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
    void lineEngineException_mapsTo400_withErrorCode() throws Exception {
        // 자기 참조 엣지(n-1 → n-1) → DagValidator가 LineEngineException(DAG_INVALID) 던짐
        String body = mockMvc.perform(post("/api/line/definitions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "definitionNm": "SelfRefFlow",
                                  "nodes": [{"nodeId":"n-1","nodeNm":"A","activityNm":"MIGRATION_WRITE","posX":0,"posY":0}],
                                  "edges": [{"edgeId":"e-1","fromNodeId":"n-1","toNodeId":"n-1"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").exists())
                .andExpect(jsonPath("$.message").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("errorCode").asText()).isNotBlank();
    }

    @Test
    void illegalArgumentException_mapsTo400_withMessage() throws Exception {
        // 존재하지 않는 정의 PUT → IllegalArgumentException
        mockMvc.perform(put("/api/line/definitions/ghost-id")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionNm\":\"Anything\",\"nodes\":[{\"nodeId\":\"n-1\",\"nodeNm\":\"A\","
                                + "\"activityNm\":\"MIGRATION_WRITE\",\"posX\":0,\"posY\":0}],\"edges\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void scheduleController_invalidCron_mapsTo400() throws Exception {
        // 정의 생성 후 잘못된 cron으로 schedule 생성 시도
        DagDefinitionRequest req = new DagDefinitionRequest(
                "ScheduleableFlow", "test", null, null, null, null,
                List.of(new DagDefinitionRequest.NodeDef("n-1", "A", "MIGRATION_WRITE",
                        null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(post("/api/line/schedules")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":\"" + defId
                                + "\",\"cronExpr\":\"not-a-cron\",\"inputData\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void responseBody_alwaysHasErrorCodeAndMessageFields() throws Exception {
        // ErrorResponse record는 errorCode + message + details 3개 필드 항상 포함
        String body = mockMvc.perform(post("/api/line/definitions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionNm\":\"\",\"nodes\":[],\"edges\":[]}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        // errorCode/message는 있어야 함 (errorCode는 IllegalArg 케이스에선 null 허용)
        assertThat(json.has("message")).isTrue();
        assertThat(json.has("errorCode")).isTrue();
        assertThat(json.has("details")).isTrue();
    }
}
