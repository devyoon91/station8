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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #138 — Builder + Run modal SLA UI 렌더 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class SlaUiRenderTest {

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
    void builder_newMode_rendersSlaSettings() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                // SLA 입력 필드
                .andExpect(content().string(containsString("id=\"slaSeconds\"")))
                .andExpect(content().string(containsString("id=\"slaAction\"")))
                .andExpect(content().string(containsString("AUTO_TERMINATE")))
                .andExpect(content().string(containsString("ALERT_ONLY")))
                // SLA가 새로 만들 때는 닫혀있음
                .andExpect(content().string(not(containsString("<details open class=\"swe-card swe-mb-lg\""))));
    }

    @Test
    void builder_editMode_preloadsSlaAndOpensDetails() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "SlaFlow", null, 3600L, "AUTO_TERMINATE",
                List.of(new DagDefinitionRequest.NodeDef("s-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/builder?id=" + defId))
                .andExpect(status().isOk())
                // 기존 값 채워짐
                .andExpect(content().string(containsString("value=\"3600\"")))
                .andExpect(content().string(containsString("AUTO_TERMINATE\" selected")))
                // SLA 있는 정의 → details 펼침
                .andExpect(content().string(containsString("<details open class=\"swe-card swe-mb-lg\"")));
    }

    @Test
    void runModal_includesSlaOverrideFields() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "RunModalFlow", null,
                List.of(new DagDefinitionRequest.NodeDef("r-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/definitions/" + defId))
                .andExpect(status().isOk())
                // Run modal SLA override
                .andExpect(content().string(containsString("id=\"run-sla-seconds\"")))
                .andExpect(content().string(containsString("id=\"run-sla-action\"")))
                .andExpect(content().string(containsString("inherit")))
                // submitRun JS에서 slaSeconds / slaAction 처리
                .andExpect(content().string(containsString("options.slaSeconds")))
                .andExpect(content().string(containsString("options.slaAction")));
    }

    @Test
    void api_acceptsAndPersistsSla() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "ApiSlaFlow", "test", 1800L, "ALERT_ONLY",
                List.of(new DagDefinitionRequest.NodeDef("a-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        org.junit.jupiter.api.Assertions.assertEquals(1800L, fetched.slaSeconds());
        org.junit.jupiter.api.Assertions.assertEquals("ALERT_ONLY", fetched.slaAction());
    }

    @Test
    void api_replaceSla_updatesValue() {
        DagDefinitionRequest v1 = new DagDefinitionRequest(
                "ReplaceSlaFlow", null, 3600L, "ALERT_ONLY",
                List.of(new DagDefinitionRequest.NodeDef("rsla-v1-a", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(v1);

        // SLA 변경 + auto-terminate로 전환. replace는 새 노드 ID 사용 (replace는 soft-delete + insert 패턴)
        DagDefinitionRequest v2 = new DagDefinitionRequest(
                "ReplaceSlaFlow", null, 60L, "AUTO_TERMINATE",
                List.of(new DagDefinitionRequest.NodeDef("rsla-v2-a", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        service.replaceDefinition(defId, v2);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        org.junit.jupiter.api.Assertions.assertEquals(60L, fetched.slaSeconds());
        org.junit.jupiter.api.Assertions.assertEquals("AUTO_TERMINATE", fetched.slaAction());
    }
}
