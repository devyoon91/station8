package com.station8.app.controller;

import com.station8.app.Application;
import com.station8.engine.entity.Credential;
import com.station8.engine.repository.CredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * #358 — {@link AdminCredentialController} 페이지/폼 round-trip 검증.
 *
 * <p>REST API({@link com.station8.app.credential.CredentialController})는 자체 테스트가 있으므로
 * 본 테스트는 페이지 렌더링 + 폼 처리 + 권한 가드에 집중. ADMIN role에서 list/new/create/edit/update/
 * delete 흐름을 한 번 도는 것을 1차 목표로.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class AdminCredentialControllerTest {

    @DynamicPropertySource
    static void credentialKey(DynamicPropertyRegistry registry) {
        registry.add("station8.credential.key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CredentialRepository credentialRepository;

    @BeforeEach
    void cleanCredentials() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM U_LINE_CREDENTIAL");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_pageRenders_empty() throws Exception {
        mvc.perform(get("/admin/credentials"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-credentials"))
                .andExpect(model().attribute("totalCount", 0))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Credentials")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void list_nonAdmin_isForbidden() throws Exception {
        mvc.perform(get("/admin/credentials"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void newForm_renders_withSupportedTypes() throws Exception {
        mvc.perform(get("/admin/credentials/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-credential-form"))
                .andExpect(model().attributeExists("supportedTypes"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openai_compatible")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("anthropic")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_openaiCompatible_persistsWithSchema() throws Exception {
        mvc.perform(post("/admin/credentials").with(csrf())
                        .param("name", "openai-prod")
                        .param("type", "openai_compatible")
                        .param("value", "sk-test-123")
                        .param("schemaBaseUrl", "https://api.openai.com/v1")
                        .param("schemaUsername", "")
                        .param("schemaHeader", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/credentials"));

        Credential c = credentialRepository.findByName("openai-prod");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo("openai_compatible");
        assertThat(c.schemaJson()).contains("baseUrl").contains("https://api.openai.com/v1");
        // 평문 value는 직접 노출되지 않음 — encrypted ciphertext 보유
        assertThat(c.valueEnc()).isNotBlank().isNotEqualTo("sk-test-123");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_httpBasic_buildsUsernameSchema() throws Exception {
        mvc.perform(post("/admin/credentials").with(csrf())
                        .param("name", "ldap-bind")
                        .param("type", "http_basic")
                        .param("value", "secret")
                        .param("schemaUsername", "svc_ldap"))
                .andExpect(status().is3xxRedirection());

        Credential c = credentialRepository.findByName("ldap-bind");
        assertThat(c.schemaJson()).contains("username").contains("svc_ldap");
        // 다른 type의 schema는 들어가지 않음
        assertThat(c.schemaJson()).doesNotContain("baseUrl");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_blankValue_keepsOriginalCiphertext() throws Exception {
        // seed
        mvc.perform(post("/admin/credentials").with(csrf())
                .param("name", "to-keep")
                .param("type", "http_bearer")
                .param("value", "first-token")).andExpect(status().is3xxRedirection());
        Credential before = credentialRepository.findByName("to-keep");
        String originalEnc = before.valueEnc();

        // update name + leave value blank
        mvc.perform(post("/admin/credentials/" + before.id()).with(csrf())
                        .param("name", "renamed")
                        .param("type", "http_bearer")
                        .param("value", ""))
                .andExpect(status().is3xxRedirection());

        Credential after = credentialRepository.findById(before.id());
        assertThat(after.name()).isEqualTo("renamed");
        assertThat(after.valueEnc()).isEqualTo(originalEnc); // ciphertext unchanged
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_newValue_rotatesCiphertext() throws Exception {
        mvc.perform(post("/admin/credentials").with(csrf())
                .param("name", "to-rotate")
                .param("type", "http_bearer")
                .param("value", "old-token")).andExpect(status().is3xxRedirection());
        Credential before = credentialRepository.findByName("to-rotate");

        mvc.perform(post("/admin/credentials/" + before.id()).with(csrf())
                .param("name", "to-rotate")
                .param("type", "http_bearer")
                .param("value", "new-token")).andExpect(status().is3xxRedirection());

        Credential after = credentialRepository.findById(before.id());
        // GCM은 IV 차이로 같은 평문도 항상 다른 ciphertext — rotate 일어났는지의 신뢰성 있는 지표
        assertThat(after.valueEnc()).isNotEqualTo(before.valueEnc());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_softDeletes_andHidesFromList() throws Exception {
        mvc.perform(post("/admin/credentials").with(csrf())
                .param("name", "to-delete")
                .param("type", "generic")
                .param("value", "x")).andExpect(status().is3xxRedirection());
        Credential c = credentialRepository.findByName("to-delete");

        mvc.perform(post("/admin/credentials/" + c.id() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(credentialRepository.findByName("to-delete")).isNull();
        List<Credential> active = credentialRepository.findAllActive();
        assertThat(active).extracting(Credential::name).doesNotContain("to-delete");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unsupportedType_flashesFail() throws Exception {
        mvc.perform(post("/admin/credentials").with(csrf())
                        .param("name", "bad")
                        .param("type", "nonexistent")
                        .param("value", "x"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashOk", false));

        assertThat(credentialRepository.findByName("bad")).isNull();
    }
}
