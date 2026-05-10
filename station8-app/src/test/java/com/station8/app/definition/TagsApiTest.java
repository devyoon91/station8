package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.repository.LineDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #142 — 라인 정의 태그 라운드트립 + 정규화 + 필터 + 클라우드 검증.
 */
@SpringBootTest(classes = Application.class)
class TagsApiTest {

    @Autowired LineDefinitionService service;
    @Autowired LineDefinitionRepository definitionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

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
    void createWithTags_persistsAndReturnsAlphabetical() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "TaggedFlow", "test", null, null, null,
                List.of("etl", "daily", "finance"),
                List.of(new DagDefinitionRequest.NodeDef("t-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        // 정규화(lowercase) + alphabetical 정렬
        assertThat(fetched.tags()).containsExactly("daily", "etl", "finance");
    }

    @Test
    void createWithTags_normalizesTrimAndLowercaseAndDedup() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "NormFlow", "test", null, null, null,
                List.of("  ETL  ", "etl", "Daily", " finance "),
                List.of(new DagDefinitionRequest.NodeDef("n-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        // ETL/etl 중복 제거, 모두 lowercase + trim
        assertThat(fetched.tags()).containsExactly("daily", "etl", "finance");
    }

    @Test
    void createWithoutTags_returnsEmptyList() {
        DagDefinitionRequest req = new DagDefinitionRequest(
                "NoTagsFlow", "test", null, null, null, null,
                List.of(new DagDefinitionRequest.NodeDef("nt-1", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(req);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        assertThat(fetched.tags()).isEmpty();
    }

    @Test
    void replaceDefinition_replacesTagsCompletely() {
        DagDefinitionRequest v1 = new DagDefinitionRequest(
                "ReplaceTagsFlow", null, null, null, null,
                List.of("v1-tag", "shared"),
                List.of(new DagDefinitionRequest.NodeDef("rt-v1-a", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        String defId = service.createDefinition(v1);

        DagDefinitionRequest v2 = new DagDefinitionRequest(
                "ReplaceTagsFlow", null, null, null, null,
                List.of("v2-tag", "shared"),  // v1-tag 제거, v2-tag 추가
                List.of(new DagDefinitionRequest.NodeDef("rt-v2-a", "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        service.replaceDefinition(defId, v2);

        DagDefinitionResponse fetched = service.getDefinition(defId);
        assertThat(fetched.tags()).containsExactly("shared", "v2-tag");
    }

    @Test
    void findDefinitionIdsByTag_returnsOnlyMatching() {
        String def1 = createDefWithTags("Flow1", List.of("etl", "daily"));
        String def2 = createDefWithTags("Flow2", List.of("etl", "ml"));
        String def3 = createDefWithTags("Flow3", List.of("ml"));

        List<String> etlMatches = definitionRepository.findDefinitionIdsByTag("etl");
        assertThat(etlMatches).containsExactlyInAnyOrder(def1, def2);

        List<String> mlMatches = definitionRepository.findDefinitionIdsByTag("ml");
        assertThat(mlMatches).containsExactlyInAnyOrder(def2, def3);
    }

    @Test
    void findAllTagsWithCount_returnsCloudSortedByCountDesc() {
        createDefWithTags("F1", List.of("etl", "daily"));
        createDefWithTags("F2", List.of("etl", "weekly"));
        createDefWithTags("F3", List.of("etl"));

        List<LineDefinitionRepository.TagCount> cloud = definitionRepository.findAllTagsWithCount();

        // etl=3, daily=1, weekly=1 — etl 먼저
        assertThat(cloud).hasSize(3);
        assertThat(cloud.get(0).tag()).isEqualTo("etl");
        assertThat(cloud.get(0).count()).isEqualTo(3);
    }

    @Test
    void findTagsForDefinitions_batchLookup_avoidsNPlusOne() {
        String def1 = createDefWithTags("BulkA", List.of("a", "b"));
        String def2 = createDefWithTags("BulkB", List.of("b", "c"));

        java.util.Map<String, List<String>> map = definitionRepository.findTagsForDefinitions(
                List.of(def1, def2));

        assertThat(map).hasSize(2);
        assertThat(map.get(def1)).containsExactly("a", "b");
        assertThat(map.get(def2)).containsExactly("b", "c");
    }

    @Test
    void deleteDefinition_cascadesTagsViaForeignKey() {
        String defId = createDefWithTags("ToDelete", List.of("temp", "test"));
        service.deleteDefinition(defId);

        // 정의는 soft-delete (DEL_FL='Y'), 태그는 ON DELETE CASCADE 적용 안 됨 (soft delete라)
        // 그러나 cloud / findDefinitionIdsByTag는 DEL_FL='N' 정의만 카운트하므로 0개 매칭
        assertThat(definitionRepository.findDefinitionIdsByTag("temp")).isEmpty();
    }

    private String createDefWithTags(String name, List<String> tags) {
        DagDefinitionRequest req = new DagDefinitionRequest(
                name, "test", null, null, null, tags,
                List.of(new DagDefinitionRequest.NodeDef("n-" + java.util.UUID.randomUUID(),
                        "A", "MIGRATION_WRITE", null, 0, 0, null)),
                List.of()
        );
        return service.createDefinition(req);
    }
}
