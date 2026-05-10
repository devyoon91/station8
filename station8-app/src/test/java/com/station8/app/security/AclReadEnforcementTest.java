package com.station8.app.security;

import com.station8.app.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * #159 — ACL READ enforcement: definitions / dashboard / DLQ / schedules 페이지 가시성 필터.
 *
 * <p>검증:</p>
 * <ul>
 *   <li>USER에게 명시 grant 있는 정의만 노출</li>
 *   <li>ACL entry 0건인 legacy 정의는 모든 인증 USER에게 노출</li>
 *   <li>ROLE_ADMIN bypass — 모든 정의 노출</li>
 *   <li>인증 없는 요청 — 아무것도 노출 (visibility = 빈 set)</li>
 *   <li>Tag cloud는 가시 정의의 태그만 합산</li>
 *   <li>Dashboard / DLQ는 가시 workflow_name 인스턴스만 노출 (SQL IN)</li>
 *   <li>Schedules는 가시 정의 ID에 묶인 스케줄만 노출</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class AclReadEnforcementTest {

    @Autowired LineAclRepository aclRepo;
    @Autowired LineAclService aclService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_TAG");
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
        jdbcTemplate.execute("DELETE FROM U_LINE_USER_ROLE");
        jdbcTemplate.execute("DELETE FROM U_LINE_USER");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        SecurityContextHolder.clearContext();
    }

    // ===== Service-level visibility helpers =====

    @Test
    void visibleDefinitionIds_admin_returnsNullForNoFilter() {
        String def1 = seedDefinition("Pipeline1");
        String def2 = seedDefinition("Pipeline2");
        // alice에게 def1 ACL 부여 → def1만 ACL 보유, def2는 legacy
        String alice = seedUser("alice");
        aclRepo.grant(def1, alice, "READ", "test");

        loginAs("admin1", "ADMIN");
        Set<String> visible = aclService.visibleDefinitionIds(java.util.List.of(def1, def2));
        assertThat(visible).as("ADMIN은 null 반환 (필터 미적용)").isNull();
    }

    @Test
    void visibleDefinitionIds_userWithGrant_seesGrantedAndLegacy() {
        String def1 = seedDefinition("Pipeline1");  // alice grant
        String def2 = seedDefinition("Pipeline2");  // bob grant only
        String def3 = seedDefinition("Pipeline3");  // legacy (no ACL)
        String alice = seedUser("alice");
        String bob = seedUser("bob");
        aclRepo.grant(def1, alice, "READ", "test");
        aclRepo.grant(def2, bob, "READ", "test");

        loginAs("alice", "USER");
        Set<String> visible = aclService.visibleDefinitionIds(java.util.List.of(def1, def2, def3));
        assertThat(visible).containsExactlyInAnyOrder(def1, def3);  // alice grant + legacy
    }

    @Test
    void visibleDefinitionIds_anonymous_returnsEmpty() {
        String def1 = seedDefinition("Pipeline1");
        // SecurityContext 없음 → anonymous
        SecurityContextHolder.clearContext();
        Set<String> visible = aclService.visibleDefinitionIds(java.util.List.of(def1));
        assertThat(visible).isEmpty();
    }

    @Test
    void visibleWorkflowNames_userWithGrant_seesGrantedNamesAndLegacy() {
        seedDefinition("WfA");  // legacy
        String defB = seedDefinition("WfB");
        String defC = seedDefinition("WfC");
        String alice = seedUser("alice");
        String bob = seedUser("bob");
        aclRepo.grant(defB, alice, "READ", "test");
        aclRepo.grant(defC, bob, "READ", "test");

        loginAs("alice", "USER");
        var allActive = java.util.List.of(
                makeStub("def-WfA", "WfA"),
                makeStub(defB, "WfB"),
                makeStub(defC, "WfC")
        );
        Set<String> visible = aclService.visibleWorkflowNames(allActive);
        assertThat(visible).containsExactlyInAnyOrder("WfA", "WfB");  // legacy + alice grant
    }

    // ===== Definitions page integration =====

    @Test
    void definitionsList_userOnlySeesGrantedAndLegacy() throws Exception {
        String def1 = seedDefinition("Granted1");
        String def2 = seedDefinition("Granted2");
        String def3 = seedDefinition("Legacy3");  // no ACL
        String def4 = seedDefinition("Forbidden4");  // bob only
        String alice = seedUser("alice");
        String bob = seedUser("bob");
        aclRepo.grant(def1, alice, "READ", "test");
        aclRepo.grant(def2, alice, "WRITE", "test");
        aclRepo.grant(def4, bob, "READ", "test");

        loginAs("alice", "USER");
        String body = mockMvc.perform(get("/line/definitions"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Granted1");
        assertThat(body).contains("Granted2");
        assertThat(body).contains("Legacy3");
        assertThat(body).doesNotContain("Forbidden4");
    }

    @Test
    void definitionsList_admin_seesAll() throws Exception {
        seedDefinition("AdminCanSee1");
        String def2 = seedDefinition("AdminCanSee2");
        String alice = seedUser("alice");
        aclRepo.grant(def2, alice, "READ", "test");  // alice 만 grant

        loginAs("admin1", "ADMIN");
        String body = mockMvc.perform(get("/line/definitions"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("AdminCanSee1");
        assertThat(body).contains("AdminCanSee2");
    }

    // ===== Tag cloud integration =====

    @Test
    void tagCloud_user_onlyCountsTagsFromVisibleDefs() throws Exception {
        String def1 = seedDefinition("WithTagAlpha");
        String def2 = seedDefinition("WithTagBeta");
        seedTag(def1, "alpha");
        seedTag(def2, "beta");
        String alice = seedUser("alice");
        String bob = seedUser("bob");
        aclRepo.grant(def1, alice, "READ", "test");  // alice는 def1만
        aclRepo.grant(def2, bob, "READ", "test");

        loginAs("alice", "USER");
        String body = mockMvc.perform(get("/line/definitions"))
                .andReturn().getResponse().getContentAsString();

        // alice는 def1(alpha)만 보이고 def2(beta) tag는 cloud에서 제외
        assertThat(body).contains("alpha");
        // beta 태그는 cloud에 노출되지 않아야 함 — 헤딩/링크/value도 모두 없음
        assertThat(body).doesNotContain(">beta<");
        assertThat(body).doesNotContain("?tag=beta");
    }

    // ===== Dashboard integration =====

    @Test
    void dashboard_userOnlySeesInstancesOfVisibleWorkflows() throws Exception {
        String def1 = seedDefinition("WfVisible");
        String def2 = seedDefinition("WfHidden");
        seedInstance("WfVisible");
        seedInstance("WfHidden");
        String alice = seedUser("alice");
        String bob = seedUser("bob");
        aclRepo.grant(def1, alice, "READ", "test");
        aclRepo.grant(def2, bob, "READ", "test");

        loginAs("alice", "USER");
        String body = mockMvc.perform(get("/line/dashboard"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("WfVisible");
        assertThat(body).doesNotContain("WfHidden");
    }

    // ===== Schedules integration =====

    @Test
    void schedules_userOnlySeesSchedulesOfVisibleDefs() throws Exception {
        String def1 = seedDefinition("WfSched1");
        String def2 = seedDefinition("WfSched2");
        String sched1 = seedSchedule(def1);
        seedSchedule(def2);
        String alice = seedUser("alice");
        String bob = seedUser("bob");
        aclRepo.grant(def1, alice, "READ", "test");
        aclRepo.grant(def2, bob, "READ", "test");

        loginAs("alice", "USER");
        String body = mockMvc.perform(get("/line/schedules"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(sched1);     // alice의 def1 스케줄만
        assertThat(body).doesNotContain(def2); // def2 ID는 스케줄 행에 노출되어야 하므로 absent
    }

    // ===== ACL repository — list helpers =====

    @Test
    void aclRepo_findDefinitionIdsWithAcl_returnsOnlyDefsWithEntries() {
        String defWith = seedDefinition("WithAcl");
        seedDefinition("Legacy");
        String userId = seedUser("u1");
        aclRepo.grant(defWith, userId, "READ", "test");

        Set<String> ids = aclRepo.findDefinitionIdsWithAcl();
        assertThat(ids).containsExactly(defWith);
    }

    @Test
    void aclRepo_findDefinitionIdsForUser_returnsUserGrants() {
        String defA = seedDefinition("A");
        String defB = seedDefinition("B");
        seedDefinition("C");  // 사용자 grant 없음
        String alice = seedUser("alice");
        aclRepo.grant(defA, alice, "READ", "test");
        aclRepo.grant(defB, alice, "WRITE", "test");

        Set<String> ids = aclRepo.findDefinitionIdsForUser(alice);
        assertThat(ids).containsExactlyInAnyOrder(defA, defB);
    }

    // ===== 헬퍼 =====

    private String seedDefinition(String name) {
        String id = "def-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL, REG_DT)
            VALUES (?, ?, 1, 'Y', 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
            """, id, name);
        return id;
    }

    private String seedUser(String username) {
        String id = "user-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_USER (ID, USERNAME, PASSWORD_HASH, ENABLED_FL, USE_FL, VIEW_FL, DEL_FL, REG_DT)
            VALUES (?, ?, '$2a$10$placeholder', 'Y', 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
            """, id, username);
        return id;
    }

    private void seedTag(String defId, String tag) {
        jdbcTemplate.update("""
            INSERT INTO U_LINE_DEFINITION_TAG (DEFINITION_ID, TAG, REG_DT, REG_ID)
            VALUES (?, ?, CURRENT_TIMESTAMP, 'test')
            """, defId, tag);
    }

    private void seedInstance(String workflowName) {
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, REG_DT, START_DT)
            VALUES (?, ?, 'COMPLETED', 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, "inst-" + UUID.randomUUID(), workflowName);
    }

    private String seedSchedule(String definitionId) {
        String id = "sch-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_SCHEDULE
              (ID, DEFINITION_ID, CRON_EXPR, NEXT_RUN_DT, PAUSED_FL,
               USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID)
            VALUES (?, ?, '0 0 * * * *', CURRENT_TIMESTAMP, 'N',
                    'Y', 'Y', 'N', CURRENT_TIMESTAMP, 'test')
            """, id, definitionId);
        return id;
    }

    private void loginAs(String username, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, "pw", Set.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** visibleWorkflowNames 테스트용 — 최소한의 LineDefinition. */
    private com.station8.engine.entity.LineDefinition makeStub(String id, String name) {
        return new com.station8.engine.entity.LineDefinition(
                id, name, /*description*/ null, /*versionNo*/ 1,
                /*activeFl*/ "Y", /*slaSeconds*/ null, /*slaAction*/ null,
                /*concurrencyPolicy*/ null,
                /*useFl*/ "Y", /*viewFl*/ "Y", /*delFl*/ "N",
                /*regDt*/ null, /*regId*/ null,
                /*editDt*/ null, /*editId*/ null
        );
    }
}
