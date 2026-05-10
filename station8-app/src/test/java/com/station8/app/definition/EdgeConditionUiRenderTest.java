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
 * #152 — Builder 엣지 조건식 UI 렌더 검증.
 *
 * <p>Mustache가 새 마크업을 깨지 않고 렌더하고 핵심 JS 훅이 응답에 포함됐는지 확인.
 * 실제 모달 동작/SpEL 평가는 클라이언트 + 서버 통합이라 별도 테스트.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class EdgeConditionUiRenderTest {

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
    void builder_rendersEdgeConditionModalAndHandlers() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                // 모달 DOM
                .andExpect(content().string(containsString("id=\"edge-cond-modal\"")))
                .andExpect(content().string(containsString("id=\"edge-cond-input\"")))
                .andExpect(content().string(containsString("id=\"edge-cond-info\"")))
                // SpEL 힌트
                .andExpect(content().string(containsString("#result")))
                .andExpect(content().string(containsString("SpEL")))
                // JS 훅
                .andExpect(content().string(containsString("openEdgeCondModal")))
                .andExpect(content().string(containsString("saveEdgeCond")))
                .andExpect(content().string(containsString("clearEdgeCond")))
                .andExpect(content().string(containsString("edgeConditions")))
                .andExpect(content().string(containsString("refreshEdgeConditionVisualization")))
                // dashed CSS
                .andExpect(content().string(containsString("has-condition")))
                .andExpect(content().string(containsString("stroke-dasharray")));
    }

    @Test
    void builder_editMode_preloadsConditionExpr() throws Exception {
        // 조건식이 있는 정의를 만들고, 편집 모드로 열었을 때 conditionExpr이 임베드되는지
        DagDefinitionRequest req = new DagDefinitionRequest(
                "ConditionalFlow", null,
                List.of(
                        new DagDefinitionRequest.NodeDef("c-a", "A", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("c-b", "B", "MIGRATION_WRITE", null, 100, 0, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef(
                        "ce-1", "c-a", "c-b", "#result['success'] == true"))
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/builder?id=" + defId))
                .andExpect(status().isOk())
                // 기존 정의 JSON에 conditionExpr 포함됨 (restoreExistingDefinition이 픽업)
                .andExpect(content().string(containsString("#result")))
                .andExpect(content().string(containsString("success")));
    }

    @Test
    void api_acceptsAndPersistsConditionExpr() throws Exception {
        // 정의 저장 → 조회 → conditionExpr 라운드트립
        DagDefinitionRequest req = new DagDefinitionRequest(
                "RoundTripFlow", null,
                List.of(
                        new DagDefinitionRequest.NodeDef("r-a", "A", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("r-b", "B", "MIGRATION_WRITE", null, 0, 0, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef(
                        "re-1", "r-a", "r-b", "#result['count'] > 10"))
        );
        String defId = service.createDefinition(req);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        assertConditionExprPresent(fetched, "#result['count'] > 10");
    }

    @Test
    void api_rejectsInvalidSpelExpr() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "InvalidSpelFlow", null,
                List.of(
                        new DagDefinitionRequest.NodeDef("i-a", "A", "MIGRATION_WRITE", null, 0, 0, null),
                        new DagDefinitionRequest.NodeDef("i-b", "B", "MIGRATION_WRITE", null, 0, 0, null)
                ),
                List.of(new DagDefinitionRequest.EdgeDef(
                        "ie-1", "i-a", "i-b", "#result[ == "))  // 잘못된 SpEL
        );
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> service.createDefinition(req));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("WF-E309") || ex.getMessage().contains("INVALID_CONDITION"),
                "WF-E309 (DAG_INVALID_CONDITION) 코드 포함 기대: " + ex.getMessage());
    }

    private void assertConditionExprPresent(DagDefinitionResponse fetched, String expectedExpr) {
        boolean found = fetched.edges().stream()
                .anyMatch(e -> expectedExpr.equals(e.conditionExpr()));
        org.junit.jupiter.api.Assertions.assertTrue(found,
                "edge에 conditionExpr='" + expectedExpr + "' 기대 — got: "
                        + fetched.edges().stream().map(DagDefinitionRequest.EdgeDef::conditionExpr).toList());
    }
}
