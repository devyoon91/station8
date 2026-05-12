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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #135 — Builder/Preview 좌측 stations list 패널 렌더링 검증.
 *
 * <p>Mustache가 strict 모드에서 새 마크업이 깨지지 않고 렌더되는지 + 핵심 DOM 노드/JS 훅이
 * 응답에 포함됐는지 확인. JS 동작(클릭 → focusStation 등)은 클라이언트 사이드라
 * 통합 테스트 범위 밖 — 마크업/스크립트 임베드까지만.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class StationsPanelRenderTest {

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
    void builder_newMode_rendersTabBarDom() throws Exception {
        // mustache 응답: 탭 DOM + 외부 JS 참조
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                // 탭 바 + 두 탭 ID
                .andExpect(content().string(containsString("class=\"swe-tab-bar\"")))
                .andExpect(content().string(containsString("id=\"tab-activities\"")))
                .andExpect(content().string(containsString("id=\"tab-stations\"")))
                // Stations 탭 body + 검색 + list 컨테이너
                .andExpect(content().string(containsString("id=\"stations-tab-body\"")))
                .andExpect(content().string(containsString("id=\"stations-search\"")))
                .andExpect(content().string(containsString("id=\"stations-list\"")))
                // subway-map.js (topologicalOrder 사용)가 빌더에서도 로드돼야 함
                .andExpect(content().string(containsString("/js/subway-map.js")))
                // #181 PR-1~4 — JS 로직은 외부 모듈
                .andExpect(content().string(containsString("/js/builder/index.js")));
    }

    /** #181 PR-1~4 — index.js: switchTab 정의 / refreshStationsList / focusBuilderNode. */
    @Test
    void indexJs_includesTabAndStationsListHandlers() throws Exception {
        mockMvc.perform(get("/js/builder/index.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("window.switchTab")))
                .andExpect(content().string(containsString("function refreshStationsList")))
                .andExpect(content().string(containsString("function focusBuilderNode")));
    }

    @Test
    void preview_rendersStationsPanelWithSearchAndList() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "PanelFlow", "stations panel test",
                List.of(
                        new DagDefinitionRequest.NodeDef("p-a", "FirstStep", "MIGRATION_WRITE",
                                null, 100, 100, null),
                        new DagDefinitionRequest.NodeDef("p-b", "SecondStep", "MIGRATION_WRITE",
                                null, 300, 100, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef("p-e", "p-a", "p-b", null))
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/definitions/" + defId))
                .andExpect(status().isOk())
                // 2-col layout
                .andExpect(content().string(containsString("class=\"swe-preview-layout\"")))
                .andExpect(content().string(containsString("class=\"swe-preview-stations-panel\"")))
                // 검색 input + list 컨테이너
                .andExpect(content().string(containsString("id=\"stations-search\"")))
                .andExpect(content().string(containsString("id=\"stations-list\"")))
                // focusStation 호출 + topologicalOrder 사용
                .andExpect(content().string(containsString("focusStation")))
                .andExpect(content().string(containsString("topologicalOrder")));
    }
}
