package com.station8.app.triggers;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #311 — LineTriggerController CRUD 회귀 가드.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>POST/GET/PUT/DELETE 정상 흐름</li>
 *   <li>ADMIN 권한 (POST/PUT/DELETE)</li>
 *   <li>key validation — regex 위반 시 400</li>
 *   <li>type whitelist — 미지원 type 400</li>
 *   <li>configJson schema — webhook의 hmacSecret 누락 시 400</li>
 *   <li>definitionId 미존재 시 400</li>
 *   <li>중복 key 시 400</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class LineTriggerControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LineDefinitionService definitionService;
    @Autowired ObjectMapper objectMapper;

    private String definitionId;

    @BeforeEach
    void setup() throws Exception {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM U_LINE_TRIGGER");

        String uniq = UUID.randomUUID().toString();
        definitionId = definitionService.createDefinition(DagDefinitionRequest.builder()
                .definitionNm("TriggerCrudTest-" + uniq)
                .description("CRUD 테스트용 라인")
                .nodes(List.of(new DagDefinitionRequest.NodeDef(
                        "n-" + uniq, "Noop", "NOOP",
                        "{}", 100, 100, null)))
                .edges(List.of())
                .build());
    }

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    private Map<String, Object> validBody(String key) {
        return Map.of(
                "definitionId", definitionId,
                "triggerType", "webhook",
                "triggerKey", key,
                "configJson", "{\"hmacSecret\":\"my-secret\"}",
                "activeFl", "Y");
    }

    // ============ POST ============

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_validRequest_returns201() throws Exception {
        String key = "test-key-" + System.currentTimeMillis();
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(validBody(key))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.triggerType").value("webhook"))
                .andExpect(jsonPath("$.triggerKey").value(key))
                .andExpect(jsonPath("$.activeFl").value("Y"));
    }

    @Test
    @WithMockUser  // ROLE_USER, not ADMIN
    void create_nonAdmin_returns403() throws Exception {
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(validBody("noadmin-key"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_invalidKeyRegex_returns400() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(validBody("INVALID_KEY"));  // uppercase
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unknownType_returns400() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(validBody("test-unknown-type"));
        body.put("triggerType", "kafka");
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_configMissingHmacSecret_returns400() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(validBody("test-no-secret"));
        body.put("configJson", "{}");
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_invalidConfigJson_returns400() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(validBody("test-bad-json"));
        body.put("configJson", "not json");
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unknownDefinition_returns400() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(validBody("test-no-def"));
        body.put("definitionId", "nonexistent-id");
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateKey_returns400() throws Exception {
        String key = "dup-key-" + System.currentTimeMillis();
        // 1st succeeds
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(validBody(key))))
                .andExpect(status().isCreated());
        // 2nd fails
        mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(validBody(key))))
                .andExpect(status().isBadRequest());
    }

    // ============ GET ============

    @Test
    @WithMockUser
    void list_returnsRegisteredTriggers() throws Exception {
        String key = "list-key-" + System.currentTimeMillis();
        mvc.perform(post("/api/line/triggers")
                        .with(csrf().asHeader())
                        .with(adminProcessor())
                        .contentType("application/json")
                        .content(json(validBody(key))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/line/triggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.triggerKey == '" + key + "')].activeFl").value("Y"));
    }

    @Test
    @WithMockUser
    void getById_returnsTrigger() throws Exception {
        String key = "get-key-" + System.currentTimeMillis();
        String body = mvc.perform(post("/api/line/triggers")
                        .with(csrf().asHeader())
                        .with(adminProcessor())
                        .contentType("application/json")
                        .content(json(validBody(key))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> created = objectMapper.readValue(body, Map.class);
        String id = (String) created.get("id");

        mvc.perform(get("/api/line/triggers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.triggerKey").value(key));
    }

    @Test
    @WithMockUser
    void getById_unknown_returns404() throws Exception {
        mvc.perform(get("/api/line/triggers/nonexistent"))
                .andExpect(status().isNotFound());
    }

    // ============ PUT / DELETE ============

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_existingTrigger_returns200() throws Exception {
        String key = "upd-key-" + System.currentTimeMillis();
        String body = mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(validBody(key))))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> created = objectMapper.readValue(body, Map.class);
        String id = (String) created.get("id");

        Map<String, Object> updateBody = new java.util.HashMap<>(validBody(key));
        updateBody.put("activeFl", "N");
        mvc.perform(put("/api/line/triggers/" + id)
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeFl").value("N"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_existingTrigger_returns200() throws Exception {
        String key = "del-key-" + System.currentTimeMillis();
        String body = mvc.perform(post("/api/line/triggers")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(validBody(key))))
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> created = objectMapper.readValue(body, Map.class);
        String id = (String) created.get("id");

        mvc.perform(delete("/api/line/triggers/" + id).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deleted"));

        // soft delete 후 조회는 404
        mvc.perform(get("/api/line/triggers/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void delete_nonAdmin_returns403() throws Exception {
        mvc.perform(delete("/api/line/triggers/any-id").with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminProcessor() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("admin").roles("ADMIN");
    }
}
