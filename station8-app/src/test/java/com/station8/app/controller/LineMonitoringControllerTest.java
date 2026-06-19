package com.station8.app.controller;

import com.station8.app.Application;
import com.station8.engine.repository.ActivityRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard "Details" 버튼 → /line/instance/{id} 라우트 회귀 가드.
 *
 * <p>본 컨트롤러에서 발견된 두 가지 결함 회귀 가드:</p>
 * <ol>
 *   <li>존재하지 않는 instance ID로 접근 시 NPE/EmptyResultDataAccessException으로 500 →
 *       명시적 404 (#275)</li>
 *   <li>활동 row의 {@code nodeId}가 {@code null}이면 JMustache가
 *       {@code "No key, method or field with name 'nodeId'"} 예외를 던져 응답이
 *       chunked 인코딩 중간에 끊김 ({@code ERR_INCOMPLETE_CHUNKED_ENCODING}). →
 *       {@link com.station8.app.config.MustacheConfig}에서 {@code nullValue("")}로
 *       compiler 설정 (본 PR)</li>
 * </ol>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class LineMonitoringControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
    }

    @Test
    void timeline_unknownInstanceId_returns404() throws Exception {
        mockMvc.perform(get("/line/instance/does-not-exist-anywhere-12345"))
                .andExpect(status().isNotFound());
    }

    @Test
    void timeline_blankPathSegment_doesNotMatch() throws Exception {
        // 빈 path는 라우트 매칭 자체가 안 되어야 — 별도 핸들러로 가지 않게 가드
        mockMvc.perform(get("/line/instance/"))
                .andExpect(status().isNotFound());
    }

    /**
     * 활동 row의 nodeId가 null인 케이스 — legacy/linear 모드 (DataMigrationLine 등) 또는
     * #267-followup의 retry 시 nodeId 손실 케이스. JMustache의 null Map value 결함이
     * 노출되지 않아야 한다 ({@link com.station8.app.config.MustacheConfig}).
     */
    @Test
    void timeline_activityWithNullNodeId_rendersWithoutChunkedTruncation() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, DEL_FL, START_DT, REG_DT)
                VALUES (?, 'LegacyFlow', 'RUNNING', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId);

        // createPending은 nodeId 인자가 없으므로 NULL nodeId로 생성됨 — 본 결함 케이스 그대로 재현
        activityRepository.createPending(instanceId, "MIGRATION_WRITE", "{\"id\":\"x\"}", null);
        activityRepository.createPending(instanceId, "MIGRATION_WRITE", "{\"id\":\"y\"}", null);

        MvcResult result = mockMvc.perform(get("/line/instance/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn();

        // 응답이 끝까지 렌더되었는지 — </html> 종료 태그 존재 = chunked 중간 끊김 없음
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("timeline 페이지가 chunked 중간에 끊기지 않고 완주해야 함 (nullValue=\"\" compiler 설정 회귀 가드)")
                .contains("</html>")
                .contains("LegacyFlow")
                .contains("MIGRATION_WRITE");
    }

    /**
     * #364 — DAG 인스턴스의 노선도(subway map)가 {@code instance.definitionId()}로 해석되는지 회귀 가드.
     * 이전엔 anchor nodeId 역조회({@code findDefinitionIdByNodeId})로 정의를 찾았으나, composite PK 이후
     * nodeId는 정의 간 충돌 가능하므로 인스턴스가 보유한 definitionId를 직접 쓴다.
     */
    @Test
    void timeline_dagInstance_rendersSubwayViaInstanceDefinitionId() throws Exception {
        String defId = "def-subway";
        jdbcTemplate.update("INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, DEL_FL) "
                + "VALUES (?, 'SubwayFlow', 1, 'Y', 'N')", defId);
        jdbcTemplate.update("INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, DEL_FL) "
                + "VALUES ('n-1', ?, 'StartStation', 'MIGRATION_WRITE', 'N')", defId);
        jdbcTemplate.update("INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, DEL_FL) "
                + "VALUES ('n-2', ?, 'EndStation', 'MIGRATION_WRITE', 'N')", defId);
        jdbcTemplate.update("INSERT INTO U_LINE_TRACK (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, DEL_FL) "
                + "VALUES ('e1', ?, 'n-1', 'n-2', 'N')", defId);

        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, DEFINITION_ID, STATUS_ST, DEL_FL, START_DT, REG_DT)
                VALUES (?, 'SubwayFlow', ?, 'RUNNING', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId, defId);
        activityRepository.createForNode(instanceId, "n-1", "MIGRATION_WRITE", "COMPLETED", null);
        activityRepository.createForNode(instanceId, "n-2", "MIGRATION_WRITE", "PENDING", null);

        MvcResult result = mockMvc.perform(get("/line/instance/" + instanceId))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("DAG 인스턴스는 definitionId로 노선도가 렌더돼야 함")
                .contains("id=\"subway-data\"")   // hasSubway=true 일 때만 출력
                .contains(defId)                   // payload.definitionId
                .contains("StartStation")          // 노드 이름이 subwayJson에 포함
                .contains("EndStation");
    }
}
