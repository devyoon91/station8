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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * #133 — 정의 상세 페이지 '최근 실행' 섹션 + Run now 모달 검증.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class DefinitionPreviewRecentRunsTest {

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
    void preview_emptyRuns_showsEmptyStateWithRunCta() throws Exception {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("EmptyRunsFlow")
                .nodes(List.of(new DagDefinitionRequest.NodeDef("n-1", "Step", "MIGRATION_WRITE",
                        null, 0, 0, null)))
                .edges(List.of())
                .build();
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/definitions/" + defId))
                .andExpect(status().isOk())
                // 통계 카드 노출 (모든 0)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Total runs")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Running")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Completed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed")))
                // 빈 상태 메시지 + CTA
                .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 실행 기록이 없습니다")))
                // Run 모달 트리거 (헤더 + empty CTA 둘 다)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openRunModal()")));
    }

    @Test
    void preview_withInstances_showsRecentRunsTable() throws Exception {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("BusyFlow")
                .nodes(List.of(new DagDefinitionRequest.NodeDef("b-1", "Step", "MIGRATION_WRITE",
                        null, 0, 0, null)))
                .edges(List.of())
                .build();
        String defId = service.createDefinition(req);

        // 인스턴스 3개 시드 — 다양한 상태
        seedInstance("BusyFlow", "RUNNING");
        seedInstance("BusyFlow", "COMPLETED");
        seedInstance("BusyFlow", "FAILED");
        // 다른 라인의 인스턴스 — 본 페이지 통계에 반영 안 되어야 함
        seedInstance("OtherFlow", "COMPLETED");

        mockMvc.perform(get("/line/definitions/" + defId))
                .andExpect(status().isOk())
                // 라인 단위 통계만 카운트 (3 + 1, 1, 1) — 정확한 값은 mustache가 표시
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("아직 실행 기록이 없습니다"))))
                // 테이블 헤더 노출
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Instance ID")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Duration")))
                // Detail 링크 (timeline)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/line/instance/")))
                // View all dashboard 링크 (workflowName prefilled)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/line/dashboard?workflowName=BusyFlow")));
    }

    @Test
    void preview_runModal_isPresentWithDefinitionIdAndEndpoint() throws Exception {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("ModalFlow")
                .nodes(List.of(new DagDefinitionRequest.NodeDef("m-1", "Step", "MIGRATION_WRITE",
                        null, 0, 0, null)))
                .edges(List.of())
                .build();
        String defId = service.createDefinition(req);

        mockMvc.perform(get("/line/definitions/" + defId))
                .andExpect(status().isOk())
                // 모달 DOM
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"run-modal\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"run-input-data\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Run \"ModalFlow\"")))
                // JS — definitionId 상수 + run endpoint
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DEFINITION_ID = '" + defId + "'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'/api/line/definitions/' + DEFINITION_ID + '/run'")))
                // 헤더에 ▶ Run now 버튼
                .andExpect(content().string(org.hamcrest.Matchers.containsString("▶ Run now")))
                // #134 + #148 — onFailure dropdown 옵션 3개
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"CONTINUE\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"ABORT\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"PAUSE_ON_FAILURE\"")));
    }

    private String seedInstance(String workflowName, String status) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE
                  (ID, WORKFLOW_NAME, STATUS_ST, DEL_FL, START_DT, REG_DT)
                VALUES (?, ?, ?, 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, workflowName, status);
        return id;
    }
}
