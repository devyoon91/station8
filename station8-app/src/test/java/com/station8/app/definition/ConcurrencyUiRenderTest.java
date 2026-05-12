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
 * #141 — Builder UI 동시 실행 정책 dropdown 렌더 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ConcurrencyUiRenderTest {

    @Autowired LineDefinitionService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

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
    void builder_newMode_rendersConcurrencyDropdown() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"concurrencyPolicy\"")))
                .andExpect(content().string(containsString("CONCURRENT")))
                .andExpect(content().string(containsString("SKIP_IF_RUNNING")))
                // #164 — Pipeline 1/2/3 옵션
                .andExpect(content().string(containsString("value=\"PIPELINE_1\"")))
                .andExpect(content().string(containsString("value=\"PIPELINE_2\"")))
                .andExpect(content().string(containsString("value=\"PIPELINE_3\"")))
                // #210 — Line settings는 Properties 패널의 탭으로 이동. "Line settings" 탭 label로 검증.
                .andExpect(content().string(containsString("Line settings")))
                .andExpect(content().string(containsString("concurrencyPolicy")));
    }

    @Test
    void builder_editMode_preselectsSkipIfRunning() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "SkipFlow", "test", null, null, "SKIP_IF_RUNNING",
                List.of(new DagDefinitionRequest.NodeDef("c-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/builder?id=" + defId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"SKIP_IF_RUNNING\" selected")));
    }

    @Test
    void builder_editMode_preselectsPipeline2() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "PipelineFlow", "test", null, null, "PIPELINE_2",
                List.of(new DagDefinitionRequest.NodeDef("p-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/builder?id=" + defId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"PIPELINE_2\" selected")));
    }
}
