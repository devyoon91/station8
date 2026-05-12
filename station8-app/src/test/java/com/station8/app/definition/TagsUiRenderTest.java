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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #142 — Builder 태그 input + definitions 페이지 태그 뱃지/필터/클라우드 렌더 검증.
 *
 * <p>#159 ACL READ enforcement 도입 후 — 렌더 테스트는 ADMIN으로 인증하여
 * 가시성 필터 우회. 필터 자체는 {@code AclReadEnforcementTest}에서 검증.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(username = "admin-render", roles = "ADMIN")
class TagsUiRenderTest {

    @Autowired LineDefinitionService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;

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
    void builder_newMode_rendersTagsInput() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"tagsInput\"")))
                .andExpect(content().string(containsString("etl, daily, finance")))
                // #210 — Line settings는 Properties 패널 탭으로 이동
                .andExpect(content().string(containsString("Line settings")));
    }

    @Test
    void builder_editMode_preloadsTags() throws Exception {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "TaggedFlow", "test", null, null, null,
                List.of("etl", "daily"),
                List.of(new DagDefinitionRequest.NodeDef("e-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/builder?id=" + defId))
                .andExpect(status().isOk())
                // tagsCsv 미리채움
                .andExpect(content().string(containsString("value=\"daily, etl\"")));
    }

    @Test
    void definitions_listPage_rendersTagBadgesAndCloud() throws Exception {
        seedWithTags("Pipeline1", List.of("etl", "daily"));
        seedWithTags("Pipeline2", List.of("ml"));

        mockMvc.perform(get("/line/definitions"))
                .andExpect(status().isOk())
                // 행별 태그 뱃지
                .andExpect(content().string(containsString(">etl<")))
                .andExpect(content().string(containsString(">daily<")))
                .andExpect(content().string(containsString(">ml<")))
                // 태그 클라우드 노출
                .andExpect(content().string(containsString("Tag cloud:")))
                // 검색/필터 form
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("name=\"tag\"")))
                // 클릭 시 필터 링크
                .andExpect(content().string(containsString("/line/definitions?tag=etl")));
    }

    @Test
    void definitions_listPage_filteredByTag_showsOnlyMatching() throws Exception {
        seedWithTags("Pipeline1", List.of("etl", "daily"));
        seedWithTags("Pipeline2", List.of("ml"));

        String body = mockMvc.perform(get("/line/definitions").param("tag", "etl"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Pipeline1만 노출, Pipeline2는 필터링됨
        assert body.contains("Pipeline1");
        assert !body.contains("Pipeline2");
    }

    @Test
    void definitions_listPage_filteredByName_partialMatch() throws Exception {
        seedWithTags("OrderFlow", List.of("etl"));
        seedWithTags("PaymentFlow", List.of("etl"));
        seedWithTags("Reporting", List.of("ml"));

        String body = mockMvc.perform(get("/line/definitions").param("name", "Flow"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assert body.contains("OrderFlow");
        assert body.contains("PaymentFlow");
        assert !body.contains("Reporting");
    }

    @Test
    void definitions_listPage_emptyResult_showsNoMatchMessage() throws Exception {
        seedWithTags("Existing", List.of("foo"));

        mockMvc.perform(get("/line/definitions").param("tag", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("nonexistent")))
                .andExpect(content().string(containsString("일치 없음")));
    }

    private void seedWithTags(String name, List<String> tags) {
        DagDefinitionRequest req = new DagDefinitionRequest(
                name, "test", null, null, null, tags,
                List.of(new DagDefinitionRequest.NodeDef("n-" + java.util.UUID.randomUUID(),
                        "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        service.createDefinition(req);
    }
}
