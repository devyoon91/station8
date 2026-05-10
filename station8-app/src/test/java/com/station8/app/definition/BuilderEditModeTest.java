package com.station8.app.definition;

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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Builder edit 모드(#99) 검증 — {@code GET /line/builder?id=...} 응답에 기존 정의 JSON이 임베드되는지.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class BuilderEditModeTest {

    @Autowired LineDefinitionService service;
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
    void newMode_rendersWithoutPreloadedDefinition() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DAG Builder")))
                // preload <script id="existing-definition-json">는 편집 모드에서만 임베드
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script id=\"existing-definition-json\""))))
                // 신규 모드 JS flag
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EDIT_MODE = false")));
    }

    /**
     * Builder UX 수정 검증:
     * - reroute 비활성 (엣지 더블클릭 split UX 차단)
     * - SVG 화살표 marker (방향성 시각화)
     * - 토폴로지 순서 badge CSS + JS 함수
     * - 포트 hover 강조 CSS
     */
    @Test
    void builder_includesUxFixesForOrderAndConnections() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("editor.reroute = false")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"dag-edge-arrow\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(".exec-order-badge")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function refreshExecutionOrder")))
                // 포트 hover 강조
                .andExpect(content().string(org.hamcrest.Matchers.containsString(".drawflow-node .output:hover")))
                // 새 사용자 안내 문구
                .andExpect(content().string(org.hamcrest.Matchers.containsString("포트에서 다른 노드의 좌측")));
    }

    @Test
    void editMode_embedsDefinitionPayloadAsJson() throws Exception {
        // 정의 등록
        DagDefinitionRequest req = new DagDefinitionRequest(
                "EditableFlow",
                "to be edited",
                List.of(
                        new DagDefinitionRequest.NodeDef("e-1", "First", "MIGRATION_WRITE",
                                "{\"k\":\"v\"}", 100, 200,
                                Map.of("source", "ops-x")),
                        new DagDefinitionRequest.NodeDef("e-2", "Second", "MIGRATION_WRITE",
                                null, 300, 200, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef("e-edge-1", "e-1", "e-2", null))
        );
        String defId = service.createDefinition(req);

        // 빌더 편집 모드 진입
        mockMvc.perform(get("/line/builder?id=" + defId))
                .andExpect(status().isOk())
                // 페이로드 임베드 확인 (script 태그)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<script id=\"existing-definition-json\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EditableFlow")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("e-1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("e-2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("e-edge-1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ops-x")))
                // 편집 모드 UI 표시
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Edit Line")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EDIT_MODE = true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EDIT_DEFINITION_ID = '" + defId + "'")));
    }

    @Test
    void editMode_unknownId_showsLoadErrorButRendersForm() throws Exception {
        mockMvc.perform(get("/line/builder?id=ghost-definition"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로드 실패")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EDIT_MODE = false")));
    }

    @Test
    void putReplace_afterCreate_persistsNewBindings() {
        DagDefinitionRequest v1 = new DagDefinitionRequest(
                "RebindFlow", "v1",
                List.of(new DagDefinitionRequest.NodeDef("rb-1", "S1", "MIGRATION_WRITE",
                        null, 0, 0, Map.of("main", "primary"))),
                List.of()
        );
        String defId = service.createDefinition(v1);

        // 편집 → bindings 변경
        DagDefinitionRequest v2 = new DagDefinitionRequest(
                "RebindFlow", "v2-edited",
                List.of(new DagDefinitionRequest.NodeDef("rb-1-new", "S1", "MIGRATION_WRITE",
                        null, 0, 0, Map.of("source", "oracle-prod", "target", "mart"))),
                List.of()
        );
        service.replaceDefinition(defId, v2);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        org.junit.jupiter.api.Assertions.assertEquals(1, fetched.nodes().size());
        org.junit.jupiter.api.Assertions.assertEquals("v2-edited", fetched.description());
        org.junit.jupiter.api.Assertions.assertEquals(
                Map.of("source", "oracle-prod", "target", "mart"),
                fetched.nodes().get(0).datasourceBindings());
    }
}
