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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #193 — DAG 정의 export 엔드포인트 테스트.
 *
 * <p>Export는 schemaVersion + 모든 메타 + nodes/edges를 JSON으로 반환하고
 * {@code Content-Disposition: attachment} 헤더로 download 트리거.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(username = "exporter", roles = "ADMIN")
class DefinitionExportTest {

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
    void export_returnsSchemaVersionAndAllMeta() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "OrderFlow", "주문 처리 — 풀 메타",
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
        String defId = service.createDefinition(req);

        String body = mockMvc.perform(get("/api/line/definitions/" + defId + "/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment; filename=\"OrderFlow-v1.json\"")))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        // schemaVersion + meta
        org.junit.jupiter.api.Assertions.assertEquals("1", root.get("schemaVersion").asText());
        org.junit.jupiter.api.Assertions.assertEquals("OrderFlow", root.get("definitionNm").asText());
        org.junit.jupiter.api.Assertions.assertEquals("주문 처리 — 풀 메타", root.get("description").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, root.get("versionNo").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(3600L, root.get("slaSeconds").asLong());
        org.junit.jupiter.api.Assertions.assertEquals("AUTO_TERMINATE", root.get("slaAction").asText());
        org.junit.jupiter.api.Assertions.assertEquals("SKIP_IF_RUNNING", root.get("concurrencyPolicy").asText());
        // tags 정렬 보존 (DB alphabetic — daily, etl)
        JsonNode tags = root.get("tags");
        org.junit.jupiter.api.Assertions.assertEquals(2, tags.size());
        org.junit.jupiter.api.Assertions.assertEquals("daily", tags.get(0).asText());
        org.junit.jupiter.api.Assertions.assertEquals("etl", tags.get(1).asText());
        // 노드 2개 + 외부 ID 보존
        JsonNode nodes = root.get("nodes");
        org.junit.jupiter.api.Assertions.assertEquals(2, nodes.size());
        org.junit.jupiter.api.Assertions.assertEquals("n-validate", nodes.get(0).get("nodeId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("Validate", nodes.get(0).get("nodeNm").asText());
        org.junit.jupiter.api.Assertions.assertEquals("MIGRATION_WRITE", nodes.get(0).get("activityNm").asText());
        // 엣지 1개 + conditionExpr 보존
        JsonNode edges = root.get("edges");
        org.junit.jupiter.api.Assertions.assertEquals(1, edges.size());
        org.junit.jupiter.api.Assertions.assertEquals("e1", edges.get(0).get("edgeId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("#result['ok'] == true",
                edges.get(0).get("conditionExpr").asText());
        // exportedAt 타임스탬프 존재
        org.junit.jupiter.api.Assertions.assertTrue(root.has("exportedAt"));
        org.junit.jupiter.api.Assertions.assertFalse(root.get("exportedAt").asText().isBlank());
    }

    @Test
    void export_minimalDefinition_emptyOptionalFields() throws Exception {
        // SLA / concurrency / tags 없는 미니멀 정의
        DagDefinitionRequest req = new DagDefinitionRequest(
                "MinimalFlow", null,
                List.of(new DagDefinitionRequest.NodeDef("only", "Only", "MIGRATION_WRITE",
                        null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        String body = mockMvc.perform(get("/api/line/definitions/" + defId + "/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        org.junit.jupiter.api.Assertions.assertEquals("MinimalFlow", root.get("definitionNm").asText());
        // null 또는 missing 모두 허용
        org.junit.jupiter.api.Assertions.assertTrue(
                root.get("slaSeconds") == null || root.get("slaSeconds").isNull());
        org.junit.jupiter.api.Assertions.assertEquals(1, root.get("nodes").size());
        org.junit.jupiter.api.Assertions.assertEquals(0, root.get("edges").size());
    }

    @Test
    void export_filenameSanitizesUnsafeChars() throws Exception {
        // 정의 이름에 영숫자/하이픈 외 문자 포함 — 파일명은 ``_``로 sanitize
        DagDefinitionRequest req = new DagDefinitionRequest(
                "주문/처리 + 결제!", null,
                List.of(new DagDefinitionRequest.NodeDef("only", "Only", "MIGRATION_WRITE",
                        null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        // "주문/처리 + 결제!" = 11자 (한글 6 + 특수문자 5) — 모두 ``_``로 치환
        mockMvc.perform(get("/api/line/definitions/" + defId + "/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("___________-v1.json")));
    }

    @Test
    void export_unknownDefinition_returnsClientError() throws Exception {
        // ADMIN 권한 통과 후 존재하지 않는 정의 → getDefinition이 IllegalArgumentException → 4xx.
        // (서비스가 NOT_FOUND가 아닌 BAD_REQUEST로 매핑하는 기존 정책 — export 외 다른 read 엔드포인트와 동일.)
        mockMvc.perform(get("/api/line/definitions/ghost-definition/export"))
                .andExpect(status().is4xxClientError());
    }
}
