package com.station8.app.security;

import com.station8.app.Application;
import com.station8.app.definition.DagDefinitionRequest;
import com.station8.app.definition.LineDefinitionService;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #140 — 라인 정의별 ACL 통합 테스트.
 *
 * <p>검증:</p>
 * <ul>
 *   <li>ACL 권한 cascade (ADMIN > WRITE/EXECUTE/SCHEDULE)</li>
 *   <li>0 entries = legacy/open</li>
 *   <li>글로벌 ROLE_ADMIN bypass</li>
 *   <li>정의 생성 시 creator에게 ADMIN auto-grant</li>
 *   <li>WRITE/EXECUTE/SCHEDULE endpoint @PreAuthorize 동작 (403 for unauthorized)</li>
 *   <li>마지막 ADMIN 강등 거부</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class LineAclTest {

    @Autowired LineAclRepository aclRepo;
    @Autowired LineAclService aclService;
    @Autowired LineUserRepository userRepo;
    @Autowired LineDefinitionService definitionService;
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
        jdbcTemplate.execute("DELETE FROM U_LINE_USER_ROLE");
        jdbcTemplate.execute("DELETE FROM U_LINE_USER");
        // SecurityContextHolder.clearContext()는 @BeforeEach에서 하면 @WithMockUser 셋업 직후 지워버려 안 됨.
        // 필요한 테스트에서 loginAs() 호출 또는 명시적 clear.
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        // 다른 테스트 클래스가 ACL FK 충돌하지 않도록 명시적 정리
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        SecurityContextHolder.clearContext();
    }

    // ===== ACL Repository =====

    @Test
    void grant_thenFindPermissions_returnsList() {
        String defId = seedDefinition("g1");
        String userId = seedUser("alice");
        aclRepo.grant(defId, userId, "WRITE", "test");
        aclRepo.grant(defId, userId, "EXECUTE", "test");

        List<String> perms = aclRepo.findPermissionsForUser(defId, userId);
        assertThat(perms).containsExactlyInAnyOrder("WRITE", "EXECUTE");
    }

    @Test
    void grant_idempotent_duplicateIgnored() {
        String defId = seedDefinition("g2");
        String userId = seedUser("bob");
        aclRepo.grant(defId, userId, "ADMIN", "test");
        aclRepo.grant(defId, userId, "ADMIN", "test");  // 두 번째는 idempotent

        assertThat(aclRepo.findPermissionsForUser(defId, userId)).containsExactly("ADMIN");
    }

    @Test
    void revoke_removesEntry() {
        String defId = seedDefinition("g3");
        String userId = seedUser("carol");
        aclRepo.grant(defId, userId, "WRITE", "test");

        aclRepo.revoke(defId, userId, "WRITE");

        assertThat(aclRepo.findPermissionsForUser(defId, userId)).isEmpty();
    }

    @Test
    void countAdminsForDefinition_works() {
        String defId = seedDefinition("g4");
        String u1 = seedUser("admin1");
        String u2 = seedUser("admin2");
        aclRepo.grant(defId, u1, "ADMIN", "test");
        aclRepo.grant(defId, u1, "WRITE", "test");
        aclRepo.grant(defId, u2, "ADMIN", "test");

        assertThat(aclRepo.countAdminsForDefinition(defId)).isEqualTo(2);
    }

    // ===== ACL Service: 권한 평가 =====

    @Test
    void canExecute_emptyAcl_legacyOpenForAuthenticated() {
        // 0 entries → 모든 인증된 USER 통과 (legacy)
        String defId = seedDefinition("legacy");
        seedUser("dave");
        loginAs("dave", "USER");

        assertThat(aclService.canExecute(defId)).isTrue();
        assertThat(aclService.canWrite(defId)).isTrue();
    }

    @Test
    void canExecute_managedAcl_userWithoutGrantDenied() {
        String defId = seedDefinition("managed");
        String adminId = seedUser("admin1");
        seedUser("eve");
        aclRepo.grant(defId, adminId, "ADMIN", "test");
        loginAs("eve", "USER");

        assertThat(aclService.canExecute(defId)).isFalse();
        assertThat(aclService.canWrite(defId)).isFalse();
    }

    @Test
    void canExecute_userWithExplicitGrant_allowed() {
        String defId = seedDefinition("exec-grant");
        String userId = seedUser("frank");
        aclRepo.grant(defId, userId, "EXECUTE", "test");
        loginAs("frank", "USER");

        assertThat(aclService.canExecute(defId)).isTrue();
        assertThat(aclService.canWrite(defId)).isFalse();  // EXECUTE는 WRITE를 함의 안 함
    }

    @Test
    void canWrite_adminCascade_allowed() {
        String defId = seedDefinition("cascade");
        String userId = seedUser("gina");
        aclRepo.grant(defId, userId, "ADMIN", "test");
        loginAs("gina", "USER");

        assertThat(aclService.canRead(defId)).isTrue();
        assertThat(aclService.canWrite(defId)).isTrue();
        assertThat(aclService.canExecute(defId)).isTrue();
        assertThat(aclService.canSchedule(defId)).isTrue();
        assertThat(aclService.canAdmin(defId)).isTrue();
    }

    @Test
    void globalAdmin_bypassesAcl() {
        // managed 정의 + 사용자에게 권한 없음, 그러나 글로벌 ROLE_ADMIN
        String defId = seedDefinition("global-bypass");
        String otherUserId = seedUser("someone");
        aclRepo.grant(defId, otherUserId, "ADMIN", "test");
        seedUser("god");
        loginAs("god", "ADMIN");  // 글로벌 ROLE_ADMIN

        assertThat(aclService.canExecute(defId)).isTrue();
        assertThat(aclService.canWrite(defId)).isTrue();
        assertThat(aclService.canAdmin(defId)).isTrue();
    }

    @Test
    void unauthenticated_returnsFalse() {
        String defId = seedDefinition("unauth");
        SecurityContextHolder.clearContext();  // 명시적 clear

        assertThat(aclService.canExecute(defId)).isFalse();
    }

    // ===== Service.createDefinition: auto-grant =====

    @Test
    @WithMockUser(username = "creator", roles = "USER")
    void createDefinition_autoGrantsAdminToCreator() {
        // 사용자 'creator'를 DB에 시드 (LineUserRepository.findByUsername이 검색)
        seedUser("creator");

        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("AutoGrantFlow")
                .nodes(List.of(new DagDefinitionRequest.NodeDef("ag-1", "A", "MIGRATION_WRITE", null, 0, 0, null)))
                .edges(List.of())
                .build();
        String defId = definitionService.createDefinition(req);

        // 'creator'에게 ADMIN 자동 부여됨
        LineUser creator = userRepo.findByUsername("creator");
        List<String> grants = aclRepo.findPermissionsForUser(defId, creator.id());
        assertThat(grants).containsExactly("ADMIN");
    }

    // ===== Endpoint @PreAuthorize =====

    @Test
    void run_unauthorizedUser_403() throws Exception {
        // 정의 생성 시 admin auto-grant. 다른 user가 run 시도 → 403
        String defId = seedDefinition("api-protected");
        String adminId = seedUser("api-admin");
        seedUser("intruder");
        aclRepo.grant(defId, adminId, "ADMIN", "test");

        mockMvc.perform(post("/api/line/definitions/" + defId + "/run")
                        .with(user("intruder").roles("USER"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void run_authorizedUser_passesAcl() throws Exception {
        // EXECUTE grant 받은 사용자는 통과 (실행 자체는 다른 이유로 실패해도 ACL은 통과)
        String defId = seedDefinitionWithStartNode("api-allowed");
        String adminId = seedUser("api-admin2");
        String userId = seedUser("runner");
        aclRepo.grant(defId, adminId, "ADMIN", "test");
        aclRepo.grant(defId, userId, "EXECUTE", "test");

        mockMvc.perform(post("/api/line/definitions/" + defId + "/run")
                        .with(user("runner").roles("USER"))
                        .contentType("application/json")
                        .content("{}"))
                // 401/403이 아니라 200 또는 다른 status (실행 실패해도 ACL 통과 의미)
                .andExpect(status -> {
                    int s = status.getResponse().getStatus();
                    if (s == 401 || s == 403) {
                        throw new AssertionError("ACL 통과해야 하는데 " + s + " 반환됨");
                    }
                });
    }

    @Test
    void delete_unauthorizedUser_403() throws Exception {
        String defId = seedDefinition("delete-protected");
        String adminId = seedUser("d-admin");
        seedUser("d-eve");
        aclRepo.grant(defId, adminId, "ADMIN", "test");

        mockMvc.perform(delete("/api/line/definitions/" + defId)
                        .with(user("d-eve").roles("USER")))
                .andExpect(status().isForbidden());
    }

    // ===== Last admin protection =====

    @Test
    void revokeAcl_lastAdminCannotSelfDemote() throws Exception {
        String defId = seedDefinition("last-admin");
        String adminId = seedUser("only-admin");
        aclRepo.grant(defId, adminId, "ADMIN", "test");

        // 자기 자신의 ADMIN 강등 시도 → form-level 거부 메시지
        mockMvc.perform(post("/line/definitions/" + defId + "/acl/revoke")
                        .param("userId", adminId)
                        .param("permission", "ADMIN")
                        .with(user("only-admin").roles("USER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // 여전히 ADMIN 권한 존재
        assertThat(aclRepo.countAdminsForDefinition(defId)).isEqualTo(1);
    }

    // ===== 헬퍼 =====

    private String seedDefinition(String name) {
        String id = "def-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, DEL_FL, REG_DT)
            VALUES (?, ?, 1, 'Y', 'N', CURRENT_TIMESTAMP)
            """, id, name);
        return id;
    }

    /** API run endpoint가 동작하려면 정의에 시작 노드 필요. */
    private String seedDefinitionWithStartNode(String name) {
        String defId = seedDefinition(name);
        jdbcTemplate.update("""
            INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, DEL_FL, REG_DT)
            VALUES (?, ?, 'A', 'MIGRATION_WRITE', 'N', CURRENT_TIMESTAMP)
            """, "n-" + UUID.randomUUID(), defId);
        return defId;
    }

    private String seedUser(String username) {
        String id = "user-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_USER (ID, USERNAME, PASSWORD_HASH, ENABLED_FL, DEL_FL, REG_DT)
            VALUES (?, ?, '$2a$10$placeholder', 'Y', 'N', CURRENT_TIMESTAMP)
            """, id, username);
        return id;
    }

    private void loginAs(String username, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, "pw", Set.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
