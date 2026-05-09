package com.station8.app.security;

import com.station8.app.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * #121 Spring Security 통합 검증: 접근 제어 + CSRF + 사용자 관리 + 비밀번호 변경.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired LineUserRepository repository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM U_LINE_USER_ROLE");
        jdbcTemplate.execute("DELETE FROM U_LINE_USER");
    }

    @Test
    void anonymousAccessToAdmin_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/datasources"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void anonymousAccessToMe_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/me/password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void userRoleCannotAccessAdmin() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("normal").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanAccessAdmin() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void postWithoutCsrfToken_isForbiddenOnAdminEndpoint() throws Exception {
        // /admin/datasources/foo/test는 CSRF 보호되는 form endpoint
        mockMvc.perform(post("/admin/datasources/some-name/test")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiPath_isCsrfExempt() throws Exception {
        // /api/**는 SecurityConfig에서 CSRF 면제 — 토큰 없이 POST 가능
        mockMvc.perform(post("/api/line/instances/ghost/terminate"))
                .andExpect(status().isNotFound());  // 인스턴스 없어 404 — 그러나 403(CSRF)은 아님
    }

    @Test
    void adminUserCreation_persistsAndAuthenticates() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .param("username", "alice")
                        .param("password", "Hello!1234")
                        .param("isAdmin", "Y")
                        .with(user("creator").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        LineUser saved = repository.findByUsername("alice");
        assertThat(saved).isNotNull();
        assertThat(saved.roles()).contains("ADMIN", "USER");
        assertThat(passwordEncoder.matches("Hello!1234", saved.passwordHash())).isTrue();
    }

    @Test
    void adminUserCreation_rejectsWeakPassword() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .param("username", "weak")
                        .param("password", "short")
                        .with(user("creator").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("userOk", false));

        assertThat(repository.findByUsername("weak")).isNull();
    }

    @Test
    void selfPasswordChange_works() throws Exception {
        // 사전 시드 — 사용자
        String userId = UUID.randomUUID().toString();
        repository.insert(new LineUser(userId, "selfpw", passwordEncoder.encode("Old!1234"),
                "Self", "Y", Set.of("USER"), "Y", "Y", "N", null, "test", null, null));

        mockMvc.perform(post("/me/password")
                        .param("currentPassword", "Old!1234")
                        .param("newPassword", "New!9876")
                        .param("confirmPassword", "New!9876")
                        .with(user("selfpw").roles("USER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("pwOk", true));

        LineUser updated = repository.findByUsername("selfpw");
        assertThat(passwordEncoder.matches("New!9876", updated.passwordHash())).isTrue();
        assertThat(passwordEncoder.matches("Old!1234", updated.passwordHash())).isFalse();
    }

    @Test
    void selfPasswordChange_rejectsWrongCurrentPassword() throws Exception {
        String userId = UUID.randomUUID().toString();
        repository.insert(new LineUser(userId, "wrongpw", passwordEncoder.encode("Old!1234"),
                "Wrong", "Y", Set.of("USER"), "Y", "Y", "N", null, "test", null, null));

        mockMvc.perform(post("/me/password")
                        .param("currentPassword", "WrongCurrent!1")
                        .param("newPassword", "New!9876")
                        .param("confirmPassword", "New!9876")
                        .with(user("wrongpw").roles("USER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("pwOk", false));

        // 비밀번호 안 바뀌어야 함
        LineUser unchanged = repository.findByUsername("wrongpw");
        assertThat(passwordEncoder.matches("Old!1234", unchanged.passwordHash())).isTrue();
    }

    @Test
    void loginPage_renders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Station8")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인")));
    }
}
