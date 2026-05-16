package com.station8.app.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.Application;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * #271 — {@link CredentialController} CRUD + masked response + 권한 검증.
 *
 * <h3>핵심 회귀 가드</h3>
 * <ul>
 *   <li>모든 응답에 평문 value / valueEnc 절대 없음</li>
 *   <li>type 화이트리스트 검증 — 미지원 type은 400</li>
 *   <li>POST/PUT/DELETE는 ADMIN role만 — USER는 403</li>
 *   <li>POST/PUT/DELETE는 CSRF 토큰 필요</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // M17 (#270) — dummy AES-GCM 256-bit 키 (Base64 of 32 random bytes). 테스트 전용.
        "station8.credential.key=FtvTfooEL5Ei04oVv5b9oMgTRxqtzn/rVN7GG7WOd80="
})
class CredentialControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CredentialRepository repository;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM U_LINE_CREDENTIAL");
    }

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    // ---- POST (ADMIN) ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_validRequest_returnsMaskedResponse() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/line/credentials")
                .with(csrf())
                .contentType("application/json")
                .content(json(Map.of(
                        "name", "slack-token",
                        "type", "http_bearer",
                        "value", "xoxb-super-secret-12345"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("slack-token"))
                .andExpect(jsonPath("$.type").value("http_bearer"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("응답에 평문 value 절대 노출 X")
                .doesNotContain("xoxb-super-secret-12345");
        assertThat(body)
                .as("응답에 valueEnc 필드 자체 노출 X")
                .doesNotContain("valueEnc")
                .doesNotContain("value\":");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unsupportedType_returns400() throws Exception {
        mockMvc.perform(post("/api/line/credentials")
                .with(csrf())
                .contentType("application/json")
                .content(json(Map.of(
                        "name", "x", "type", "weird_type", "value", "v"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unsupported")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankValue_returns400() throws Exception {
        mockMvc.perform(post("/api/line/credentials")
                .with(csrf())
                .contentType("application/json")
                .content(json(Map.of(
                        "name", "x", "type", "generic", "value", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateName_returns400() throws Exception {
        String body = json(Map.of("name", "dup", "type", "generic", "value", "v1"));
        mockMvc.perform(post("/api/line/credentials").with(csrf())
                .contentType("application/json").content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/line/credentials").with(csrf())
                .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void create_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/line/credentials")
                .with(csrf())
                .contentType("application/json")
                .content(json(Map.of("name", "x", "type", "generic", "value", "v"))))
                .andExpect(status().isForbidden());
    }

    // ---- GET (any authenticated) ----

    @Test
    @WithMockUser(roles = "USER")
    void list_asUser_returnsItemsWithoutValue() throws Exception {
        seed("alpha", "generic");
        seed("beta", "api_key");

        MvcResult result = mockMvc.perform(get("/api/line/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("valueEnc")
                .doesNotContain("\"value\"");
    }

    @Test
    @WithMockUser(roles = "USER")
    void get_unknown_returns404() throws Exception {
        mockMvc.perform(get("/api/line/credentials/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void get_existing_returnsMasked() throws Exception {
        String id = seed("get-me", "generic");
        mockMvc.perform(get("/api/line/credentials/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("get-me"))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.valueEnc").doesNotExist());
    }

    // ---- PUT (ADMIN) ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_withValue_rotates_andResponseMasked() throws Exception {
        String id = seed("rotate-me", "generic");
        var encBefore = repository.findById(id).valueEnc();

        mockMvc.perform(put("/api/line/credentials/" + id)
                .with(csrf())
                .contentType("application/json")
                .content(json(Map.of(
                        "name", "rotate-me", "type", "generic",
                        "value", "BRAND-NEW-SECRET"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").doesNotExist());

        var encAfter = repository.findById(id).valueEnc();
        assertThat(encAfter).as("value rotate 시 ciphertext 변경").isNotEqualTo(encBefore);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_withoutValue_keepsCiphertext() throws Exception {
        String id = seed("keep-me", "generic");
        var encBefore = repository.findById(id).valueEnc();

        mockMvc.perform(put("/api/line/credentials/" + id)
                .with(csrf())
                .contentType("application/json")
                .content(json(Map.of(
                        "name", "renamed", "type", "generic"))))  // value 없음
                .andExpect(status().isOk());

        var after = repository.findById(id);
        assertThat(after.valueEnc()).as("value 없으면 기존 ciphertext 유지").isEqualTo(encBefore);
        assertThat(after.name()).isEqualTo("renamed");
    }

    @Test
    @WithMockUser(roles = "USER")
    void update_asNonAdmin_returns403() throws Exception {
        String id = seed("locked", "generic");
        mockMvc.perform(put("/api/line/credentials/" + id)
                .with(csrf())
                .contentType("application/json")
                .content(json(Map.of("name", "locked", "type", "generic"))))
                .andExpect(status().isForbidden());
    }

    // ---- DELETE (ADMIN) ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_existing_softDeletes() throws Exception {
        String id = seed("remove-me", "generic");
        mockMvc.perform(delete("/api/line/credentials/" + id).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deleted"));

        assertThat(repository.findById(id)).as("soft delete 후 조회 안 됨").isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_returns404() throws Exception {
        mockMvc.perform(delete("/api/line/credentials/nope").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void delete_asNonAdmin_returns403() throws Exception {
        String id = seed("protected", "generic");
        mockMvc.perform(delete("/api/line/credentials/" + id).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(repository.findById(id)).as("403이면 soft-delete 안 되어야 함").isNotNull();
    }

    // ---- helpers ----

    /** 직접 INSERT — Controller POST 검증과 무관하게 fixture 준비. */
    private String seed(String name, String type) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/line/credentials")
                .with(csrf())
                .with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("test").roles("ADMIN"))
                .contentType("application/json")
                .content(json(Map.of(
                        "name", name, "type", type, "value", "seed-" + name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }
}
