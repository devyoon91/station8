package com.station8.app.definition;

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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * #193 — DAG 정의 import + 라운드트립 (export → import → 검증) 테스트.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(username = "importer", roles = "ADMIN")
class DefinitionImportTest {

    @Autowired LineDefinitionService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_TAG");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    @Test
    void roundTrip_export_then_import_reproducesDefinition() throws Exception {
        // 1) 풀 메타 정의 생성
        DagDefinitionRequest req = new DagDefinitionRequest(
                "OrderFlow", "원본 정의",
                3600L, "AUTO_TERMINATE", "SKIP_IF_RUNNING",
                List.of("etl", "daily"),
                List.of(
                        new DagDefinitionRequest.NodeDef("n-validate", "Validate", "MIGRATION_WRITE",
                                "{\"k\":\"v\"}", 100, 200, Map.of("src", "ops-x")),
                        new DagDefinitionRequest.NodeDef("n-charge", "Charge", "MIGRATION_WRITE",
                                null, 300, 200, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef(
                        "e1", "n-validate", "n-charge", "#result['ok'] == true"))
        );
        String originalId = service.createDefinition(req);

        // 2) Export
        String exportedJson = mockMvc.perform(get("/api/line/definitions/" + originalId + "/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 3) Import (newVersion 기본) — 같은 이름이라 v2로 추가됨
        String importResp = mockMvc.perform(post("/api/line/definitions/import")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exportedJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode result = objectMapper.readTree(importResp);
        org.junit.jupiter.api.Assertions.assertEquals("OrderFlow", result.get("definitionNm").asText());
        org.junit.jupiter.api.Assertions.assertEquals("NEW_VERSION", result.get("appliedPolicy").asText());

        // 4) 새로 import된 정의 조회 → 메타/노드/엣지 동일 (단 versionNo는 +1)
        String newId = result.get("definitionId").asText();
        DagDefinitionResponse imported = service.getDefinition(newId);
        org.junit.jupiter.api.Assertions.assertEquals("OrderFlow", imported.definitionNm());
        org.junit.jupiter.api.Assertions.assertEquals(2, imported.versionNo());
        org.junit.jupiter.api.Assertions.assertEquals(3600L, imported.slaSeconds());
        org.junit.jupiter.api.Assertions.assertEquals("AUTO_TERMINATE", imported.slaAction());
        org.junit.jupiter.api.Assertions.assertEquals("SKIP_IF_RUNNING", imported.concurrencyPolicy());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("daily", "etl"), imported.tags());
        // import 시 nodeId/edgeId는 재발급(U_LINE_STATION.ID가 글로벌 PK) — nodeNm/activityNm/bindings 동일성으로 확인
        org.junit.jupiter.api.Assertions.assertEquals(2, imported.nodes().size());
        var validateNode = imported.nodes().stream()
                .filter(n -> "Validate".equals(n.nodeNm())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("MIGRATION_WRITE", validateNode.activityNm());
        org.junit.jupiter.api.Assertions.assertEquals(Map.of("src", "ops-x"), validateNode.datasourceBindings());
        org.junit.jupiter.api.Assertions.assertNotEquals("n-validate", validateNode.nodeId(),
                "nodeId는 새로 발급돼야 함 (PK 충돌 회피)");
        org.junit.jupiter.api.Assertions.assertEquals(1, imported.edges().size());
        org.junit.jupiter.api.Assertions.assertEquals("#result['ok'] == true", imported.edges().get(0).conditionExpr());
        // edge가 재발급된 노드 ID를 정확히 가리키는지 (참조 일관성)
        var chargeNode = imported.nodes().stream()
                .filter(n -> "Charge".equals(n.nodeNm())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(validateNode.nodeId(), imported.edges().get(0).fromNodeId());
        org.junit.jupiter.api.Assertions.assertEquals(chargeNode.nodeId(), imported.edges().get(0).toNodeId());
    }

    @Test
    void import_renamePolicy_appendsCounterSuffix() throws Exception {
        // 기존 정의가 같은 이름으로 존재
        seedSimple("CollideFlow");

        String payload = """
                {
                  "schemaVersion": "1",
                  "definitionNm": "CollideFlow",
                  "description": null,
                  "nodes": [{"nodeId":"n-1","nodeNm":"Only","activityNm":"MIGRATION_WRITE","posX":0,"posY":0}],
                  "edges": []
                }
                """;
        String resp = mockMvc.perform(post("/api/line/definitions/import?onConflict=rename")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode j = objectMapper.readTree(resp);
        org.junit.jupiter.api.Assertions.assertEquals("CollideFlow (1)", j.get("definitionNm").asText());
        org.junit.jupiter.api.Assertions.assertEquals("RENAME", j.get("appliedPolicy").asText());
    }

    @Test
    void import_rejectPolicy_returnsErrorOnConflict() throws Exception {
        seedSimple("BlockFlow");

        String payload = """
                {
                  "schemaVersion": "1",
                  "definitionNm": "BlockFlow",
                  "description": null,
                  "nodes": [{"nodeId":"n-1","nodeNm":"Only","activityNm":"MIGRATION_WRITE","posX":0,"posY":0}],
                  "edges": []
                }
                """;
        mockMvc.perform(post("/api/line/definitions/import?onConflict=reject")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void import_unknownSchemaVersion_returnsError() throws Exception {
        String payload = """
                {
                  "schemaVersion": "99",
                  "definitionNm": "FutureFlow",
                  "nodes": [{"nodeId":"n-1","nodeNm":"Only","activityNm":"MIGRATION_WRITE","posX":0,"posY":0}],
                  "edges": []
                }
                """;
        mockMvc.perform(post("/api/line/definitions/import")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void import_unknownActivity_returnsError() throws Exception {
        // schemaVersion은 OK지만 activityNm이 LineRegistry에 없는 케이스 → DagValidator 거부
        String payload = """
                {
                  "schemaVersion": "1",
                  "definitionNm": "BadActivityFlow",
                  "nodes": [{"nodeId":"n-1","nodeNm":"Only","activityNm":"NONEXISTENT_XYZ","posX":0,"posY":0}],
                  "edges": []
                }
                """;
        mockMvc.perform(post("/api/line/definitions/import")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void import_invalidOnConflictParam_returnsError() throws Exception {
        String payload = """
                {
                  "schemaVersion": "1",
                  "definitionNm": "X",
                  "nodes": [{"nodeId":"n-1","nodeNm":"Only","activityNm":"MIGRATION_WRITE","posX":0,"posY":0}],
                  "edges": []
                }
                """;
        mockMvc.perform(post("/api/line/definitions/import?onConflict=bogus")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is4xxClientError());
    }

    private void seedSimple(String name) {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm(name)
                .nodes(List.of(new DagDefinitionRequest.NodeDef("s-1", "Only", "MIGRATION_WRITE",
                        null, 0, 0, null)))
                .edges(List.of())
                .build();
        service.createDefinition(req);
    }
}
