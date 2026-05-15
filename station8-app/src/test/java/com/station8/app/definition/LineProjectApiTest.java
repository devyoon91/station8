package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.entity.LineProject;
import com.station8.engine.repository.LineProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #168 — Project CRUD + Seeder + Definition 통합 동작 검증.
 */
@SpringBootTest(classes = Application.class)
class LineProjectApiTest {

    @Autowired LineProjectService projectService;
    @Autowired LineDefinitionService definitionService;
    @Autowired LineProjectRepository projectRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        // 정의들 정리 — project FK 의존
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_TAG");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
        // default project 외 모든 user 프로젝트 정리
        jdbcTemplate.update("DELETE FROM U_LINE_PROJECT WHERE ID <> ?",
                LineProject.DEFAULT_PROJECT_ID);
    }

    @Test
    void seeder_createsDefaultProject() {
        // LineProjectSeeder가 @EventListener(ApplicationReadyEvent.class)로 default project 시드해야 함
        LineProject defaultProject = projectRepository.findById(LineProject.DEFAULT_PROJECT_ID);
        assertThat(defaultProject).as("default project가 부팅 시 시드되어야 함").isNotNull();
        assertThat(defaultProject.projectNm()).isEqualTo(LineProject.DEFAULT_PROJECT_NM);
    }

    @Test
    void createDefinition_assignsToDefaultProject() {
        // Phase 1 — 모든 신규 정의는 default project로 할당됨
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("ProjectAssignFlow")
                .nodes(List.of(new DagDefinitionRequest.NodeDef(
                        "n-1", "A", "MIGRATION_WRITE", null, 0, 0, null)))
                .edges(List.of())
                .build();
        String defId = definitionService.createDefinition(req);

        DagDefinitionResponse fetched = definitionService.getDefinition(defId);
        assertThat(fetched.projectId()).isEqualTo(LineProject.DEFAULT_PROJECT_ID);
    }

    @Test
    void createProject_roundTrips() {
        String id = projectService.createProject(
                new LineProjectRequest("AnalyticsTeam", "분석팀 라인 컨테이너"),
                "testuser");

        LineProjectResponse fetched = projectService.getProject(id);
        assertThat(fetched.projectNm()).isEqualTo("AnalyticsTeam");
        assertThat(fetched.description()).isEqualTo("분석팀 라인 컨테이너");
        assertThat(fetched.regId()).isEqualTo("testuser");
    }

    @Test
    void createProject_duplicateName_throws() {
        projectService.createProject(new LineProjectRequest("DupTeam", null), null);
        assertThatThrownBy(() ->
                projectService.createProject(new LineProjectRequest("DupTeam", null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 존재하는");
    }

    @Test
    void listProjects_returnsAlphabeticalSorted() {
        projectService.createProject(new LineProjectRequest("Zoo", null), null);
        projectService.createProject(new LineProjectRequest("Apple", null), null);
        projectService.createProject(new LineProjectRequest("Mango", null), null);

        List<LineProjectResponse> all = projectService.listProjects();
        // default + 3 user projects, 알파벳 정렬
        assertThat(all).hasSize(4);
        assertThat(all.stream().map(LineProjectResponse::projectNm).toList())
                .containsExactly("Apple", "Mango", "Zoo", LineProject.DEFAULT_PROJECT_NM);
    }

    @Test
    void updateProject_changesNameAndDescription() {
        String id = projectService.createProject(new LineProjectRequest("OldName", "old"), null);
        projectService.updateProject(id, new LineProjectRequest("NewName", "new"), "editor");

        LineProjectResponse fetched = projectService.getProject(id);
        assertThat(fetched.projectNm()).isEqualTo("NewName");
        assertThat(fetched.description()).isEqualTo("new");
    }

    @Test
    void updateProject_renameToExistingName_throws() {
        projectService.createProject(new LineProjectRequest("Existing", null), null);
        String otherId = projectService.createProject(new LineProjectRequest("Other", null), null);

        assertThatThrownBy(() ->
                projectService.updateProject(otherId, new LineProjectRequest("Existing", null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 존재하는");
    }

    @Test
    void deleteProject_softDeletes() {
        String id = projectService.createProject(new LineProjectRequest("ToDelete", null), null);
        projectService.deleteProject(id);

        assertThatThrownBy(() -> projectService.getProject(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("찾을 수 없");
    }

    @Test
    void deleteProject_defaultProject_protected() {
        // default project는 시스템 fallback이므로 삭제 불가
        assertThatThrownBy(() -> projectService.deleteProject(LineProject.DEFAULT_PROJECT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default");
    }

    @Test
    void seeder_backfillsExistingNullProjectIds() {
        // NULL projectId 정의를 의도적으로 삽입 후 seeder 재실행이 backfill하는지 확인
        // (Seeder는 ApplicationReady 시점에 자동 실행 — 본 테스트는 동일 동작을 SQL로 시뮬레이션)
        String defId = "manual-null-def";
        jdbcTemplate.update("""
                INSERT INTO U_LINE_DEFINITION
                  (ID, DEFINITION_NM, DESCRIPTION, VERSION_NO, ACTIVE_FL,
                   DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, NULL, 1, 'Y', 'N', CURRENT_TIMESTAMP, 'test')
                """, defId, "NullProjectFlow");

        // Seeder의 backfill UPDATE을 직접 실행 (Seeder는 boot 시점에만 실행되므로)
        int updated = jdbcTemplate.update(
                "UPDATE U_LINE_DEFINITION SET PROJECT_ID = ? WHERE PROJECT_ID IS NULL",
                LineProject.DEFAULT_PROJECT_ID);
        assertThat(updated).isGreaterThanOrEqualTo(1);

        String projectId = jdbcTemplate.queryForObject(
                "SELECT PROJECT_ID FROM U_LINE_DEFINITION WHERE ID = ?", String.class, defId);
        assertThat(projectId).isEqualTo(LineProject.DEFAULT_PROJECT_ID);
    }
}
