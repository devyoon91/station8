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
     * #181 PR-1~4 — index.js entrypoint: editor 생성 + 부트스트랩 + 잔존 함수(populateProps,
     * refreshNodeLabels, switchTab/switchPropTab, refreshStationsList, deleteNode 등).
     * 다음 함수들은 별도 모듈로 이동 — index.js에 더 이상 없음:
     * <ul>
     *   <li>PR-2 — buildBuilderGraph / escapeHtmlBuilder (graph-model.js)</li>
     *   <li>PR-3 — saveDefinition / restoreExistingDefinition (form-serializer.js)</li>
     *   <li>PR-4 — showCtxMenu (ctx-menu.js), shouldIgnoreShortcut (shortcuts.js),
     *       openMobilePanel (mobile-overlay.js), parseConnectionFromElement (edge-condition-modal.js),
     *       setupTouchLongPress (touch-longpress.js)</li>
     * </ul>
     */
    @Test
    void builder_externalIndexJs_servesEntrypointOnly() throws Exception {
        mockMvc.perform(get("/js/builder/index.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("editor.reroute = false")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("new Drawflow")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function refreshNodeLabels")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function populateProps")))
                // 이동된 함수들은 부재
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("function buildBuilderGraph"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("function escapeHtmlBuilder"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("async function saveDefinition"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("function restoreExistingDefinition"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("function showCtxMenu"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("function shouldIgnoreShortcut"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("function openMobilePanel"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("function parseConnectionFromElement"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("setupTouchLongPress"))));
    }

    /** #181 PR-4 — mobile-overlay.js: FAB 토글 / tap-to-add */
    @Test
    void builder_externalMobileOverlayJs_servesFAB() throws Exception {
        mockMvc.perform(get("/js/builder/mobile-overlay.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function isMobile")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function openMobilePanel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function closeMobilePanels")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function toggleMobilePanel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function addActivityNodeAtCenter")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MOBILE_MQ")));
    }

    /** #181 PR-4 — ctx-menu.js: 우클릭 컨텍스트 메뉴 (touch long-press가 합성 dispatch) */
    @Test
    void builder_externalCtxMenuJs_servesContextMenu() throws Exception {
        mockMvc.perform(get("/js/builder/ctx-menu.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function showCtxMenu")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function closeCtxMenu")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function ctxMoveFocus")))
                // 노드/엣지 contextmenu 핸들러 + capture phase
                .andExpect(content().string(org.hamcrest.Matchers.containsString("addEventListener('contextmenu'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("capture: true")))
                // Delete node 메뉴 항목에 단축키 hint
                .andExpect(content().string(org.hamcrest.Matchers.containsString("shortcut: 'Del'")));
    }

    /** #181 PR-4 — edge-condition-modal.js: 엣지 조건식 모달 + 연결 파싱/해제 + 시각화 */
    @Test
    void builder_externalEdgeConditionModalJs_servesEdgeFeatures() throws Exception {
        mockMvc.perform(get("/js/builder/edge-condition-modal.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function parseConnectionFromElement")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function disconnectAllEdges")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function disconnectEdge")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("edgeConditions")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openEdgeCondModal")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("saveEdgeCond")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function refreshEdgeConditionVisualization")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function bindEdgeConditionEditorEvents")));
    }

    /** #181 PR-4 — shortcuts.js: 키보드 단축키 (Delete / Esc / F / ? / Ctrl+S) */
    @Test
    void builder_externalShortcutsJs_servesKeydownHandler() throws Exception {
        mockMvc.perform(get("/js/builder/shortcuts.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openShortcutsModal")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("closeShortcutsModal")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function shouldIgnoreShortcut")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function isAnyModalOpen")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("e.key === 'Delete'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("e.key === 'Escape'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("e.key === '?'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("e.ctrlKey || e.metaKey")));
    }

    /** #181 PR-4 — touch-longpress.js: 500ms long-press → 합성 contextmenu dispatch */
    @Test
    void builder_externalTouchLongpressJs_servesIIFE() throws Exception {
        mockMvc.perform(get("/js/builder/touch-longpress.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("setupTouchLongPress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LONG_PRESS_MS")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MOVE_TOLERANCE")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("new MouseEvent('contextmenu'")));
    }

    /**
     * #181 PR-3 — form-serializer.js가 saveDefinition / restoreExistingDefinition을 제공하는지 검증.
     */
    @Test
    void builder_externalFormSerializerJs_servesIO() throws Exception {
        mockMvc.perform(get("/js/builder/form-serializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("async function saveDefinition")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function restoreExistingDefinition")))
                // POST/PUT 라우팅 분기 + SLA/Concurrency/Tags 직렬화
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/line/definitions")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EDIT_MODE")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("existing-definition-json")));
    }

    /**
     * #181 PR-2 — graph-model.js가 buildBuilderGraph / escapeHtmlBuilder를 제공하는지 검증.
     */
    @Test
    void builder_externalGraphModelJs_servesPureHelpers() throws Exception {
        mockMvc.perform(get("/js/builder/graph-model.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function buildBuilderGraph(editor)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("function escapeHtmlBuilder")));
    }

    /**
     * #181 PR-1~4 — builder.mustache의 <script> load order 보장.
     * classic script global scope 공유 의존 — index.js (entrypoint, editor 생성)가 마지막이어야 함.
     * 본 모듈들은 모두 그 전에 로드해 함수/상태 선언이 끝나 있어야 한다.
     */
    @Test
    void builder_loadsAllModulesBeforeIndex() throws Exception {
        String body = mockMvc.perform(get("/line/builder"))
                .andReturn().getResponse().getContentAsString();
        int graphIdx = body.indexOf("src=\"/js/builder/graph-model.js\"");
        int formIdx = body.indexOf("src=\"/js/builder/form-serializer.js\"");
        int mobileIdx = body.indexOf("src=\"/js/builder/mobile-overlay.js\"");
        int shortcutsIdx = body.indexOf("src=\"/js/builder/shortcuts.js\"");
        int ctxIdx = body.indexOf("src=\"/js/builder/ctx-menu.js\"");
        int touchIdx = body.indexOf("src=\"/js/builder/touch-longpress.js\"");
        int edgeIdx = body.indexOf("src=\"/js/builder/edge-condition-modal.js\"");
        int indexIdx = body.indexOf("src=\"/js/builder/index.js\"");

        for (var entry : java.util.Map.of(
                "graph-model.js", graphIdx,
                "form-serializer.js", formIdx,
                "mobile-overlay.js", mobileIdx,
                "shortcuts.js", shortcutsIdx,
                "ctx-menu.js", ctxIdx,
                "touch-longpress.js", touchIdx,
                "edge-condition-modal.js", edgeIdx,
                "index.js", indexIdx).entrySet()) {
            org.junit.jupiter.api.Assertions.assertTrue(entry.getValue() >= 0,
                    entry.getKey() + " <script> 참조 없음");
        }
        // 모든 보조 모듈이 index.js 보다 먼저 와야 함
        int[] preIndex = { graphIdx, formIdx, mobileIdx, shortcutsIdx, ctxIdx, touchIdx, edgeIdx };
        for (int idx : preIndex) {
            org.junit.jupiter.api.Assertions.assertTrue(idx < indexIdx,
                    "모듈 idx=" + idx + " 가 index.js idx=" + indexIdx + " 보다 뒤 — entrypoint는 마지막이어야 함");
        }
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
