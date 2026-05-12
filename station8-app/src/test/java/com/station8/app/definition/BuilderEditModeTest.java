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
                // #181 PR-1 — Builder JS는 /js/builder/index.js로 외부화. data-island JSON으로 모드 식별값 주입.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"editMode\": false")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"editDefinitionId\": null")))
                // external 스크립트 참조 존재
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/builder/index.js")));
    }

    /**
     * Builder UX — HTML/CSS는 mustache, JS 로직은 /js/builder/index.js. 본 테스트는 mustache 응답만 검증.
     * (외부 JS 내용은 builder_externalIndexJs_servesCoreLogic 가 별도 검증)
     */
    @Test
    void builder_includesUxFixesForOrderAndConnections() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                // mustache 응답에 남아 있는 HTML/CSS만 검증
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"dag-edge-arrow\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(".drawflow-node .output:hover")))
                // 새 사용자 안내 문구
                .andExpect(content().string(org.hamcrest.Matchers.containsString("포트에서 다른 노드의 좌측")));
    }

    /**
     * #181 PR-1 — 외부화된 /js/builder/index.js가 핵심 로직을 포함하는지 직접 검증.
     */
    @Test
    void builder_externalIndexJs_servesCoreLogic() throws Exception {
        mockMvc.perform(get("/js/builder/index.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("editor.reroute = false")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("new Drawflow")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function refreshNodeLabels")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function saveDefinition")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function restoreExistingDefinition")));
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
                // #181 PR-1 — data-island JSON으로 모드 식별값 전달
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"editMode\": true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"editDefinitionId\": \"" + defId + "\"")));
    }

    @Test
    void editMode_unknownId_showsLoadErrorButRendersForm() throws Exception {
        mockMvc.perform(get("/line/builder?id=ghost-definition"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로드 실패")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"editMode\": false")));
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
