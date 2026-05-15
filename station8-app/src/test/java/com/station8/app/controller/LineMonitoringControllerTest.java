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
}
