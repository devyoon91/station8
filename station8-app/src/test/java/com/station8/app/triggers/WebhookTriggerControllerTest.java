package com.station8.app.triggers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.Application;
import com.station8.app.definition.DagDefinitionRequest;
import com.station8.app.definition.LineDefinitionService;
import com.station8.engine.crypto.CredentialCrypto;
import com.station8.engine.entity.Credential;
import com.station8.engine.entity.LineTrigger;
import com.station8.engine.repository.CredentialRepository;
import com.station8.engine.repository.LineTriggerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #310 — WebhookTriggerController 회귀 가드.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>HMAC 검증 통과 → 라인 시작 + instanceId 응답</li>
 *   <li>잘못된 signature → 401</li>
 *   <li>존재 안 하는 key → 404</li>
 *   <li>비활성 trigger → 404</li>
 *   <li>signature 없음 → 401</li>
 *   <li>vault에 hmacSecret credential 없음 → 500</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "station8.credential.key=FtvTfooEL5Ei04oVv5b9oMgTRxqtzn/rVN7GG7WOd80="
})
class WebhookTriggerControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LineDefinitionService definitionService;
    @Autowired LineTriggerRepository triggerRepository;
    @Autowired CredentialRepository credentialRepository;
    @Autowired CredentialCrypto crypto;
    @Autowired ObjectMapper objectMapper;

    private static final String SECRET = "super-secret-webhook-key-12345";
    private static final String CRED_NAME = "test-webhook-secret";

    private String definitionId;
    private String triggerKey;

    @BeforeEach
    void setup() throws Exception {
        // schema 적용
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM U_LINE_TRIGGER");
        jdbcTemplate.execute("DELETE FROM U_LINE_CREDENTIAL");

        // 데모 line definition — 단일 노드, MIGRATION_WRITE 사용 (앱에 이미 등록된 활동).
        // nodeId는 매 테스트 unique (U_LINE_STATION PK 충돌 방지).
        String uniq = UUID.randomUUID().toString();
        definitionId = definitionService.createDefinition(DagDefinitionRequest.builder()
                .definitionNm("WebhookTestLine-" + uniq)
                .description("webhook trigger 테스트용")
                .nodes(List.of(new DagDefinitionRequest.NodeDef(
                        "n-" + uniq, "Migrate", "MIGRATION_WRITE",
                        "{\"id\":\"webhook-test\",\"content\":\"ok\"}",
                        100, 100, null)))
                .edges(List.of())
                .build());

        // HMAC secret을 vault에 등록 (webhook_hmac type)
        Credential cred = new Credential(
                UUID.randomUUID().toString(),
                CRED_NAME,
                "webhook_hmac",
                crypto.encrypt(SECRET),
                null,
                "N",
                LocalDateTime.now(),
                "test",
                null, null);
        credentialRepository.insert(cred);

        // webhook trigger 등록
        triggerKey = "test-key-" + System.currentTimeMillis();
        String configJson = objectMapper.writeValueAsString(
                java.util.Map.of("hmacSecret", CRED_NAME));
        triggerRepository.insert(new LineTrigger(
                UUID.randomUUID().toString(),
                definitionId,
                "webhook",
                triggerKey,
                configJson,
                "Y",
                "N",
                LocalDateTime.now(),
                "test",
                null, null));
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void validSignature_launchesInstance() throws Exception {
        String body = "{\"orderId\":\"42\"}";
        String sig = hmac(SECRET, body);

        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .header("X-Signature", sig)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.definitionId").value(definitionId));
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        String body = "{\"orderId\":\"42\"}";
        String wrongSig = hmac("different-secret", body);

        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .header("X-Signature", wrongSig)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid signature"));
    }

    @Test
    void missingSignature_returns401() throws Exception {
        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("X-Signature")));
    }

    @Test
    void unknownKey_returns404() throws Exception {
        String sig = hmac(SECRET, "{}");
        mvc.perform(post("/api/triggers/webhook/no-such-key")
                        .header("X-Signature", sig)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.key").value("no-such-key"));
    }

    @Test
    void inactiveTrigger_returns404() throws Exception {
        // ACTIVE_FL = 'N'으로 갱신
        jdbcTemplate.update(
                "UPDATE U_LINE_TRIGGER SET ACTIVE_FL = 'N' WHERE TRIGGER_KEY = ?",
                triggerKey);
        String sig = hmac(SECRET, "{}");
        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .header("X-Signature", sig)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hmacSecretCredentialMissing_returns500() throws Exception {
        // vault에서 credential 삭제 (config는 그대로 — 운영 misconfig 시나리오)
        jdbcTemplate.update("DELETE FROM U_LINE_CREDENTIAL WHERE NAME = ?", CRED_NAME);
        String sig = hmac(SECRET, "{}");
        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .header("X-Signature", sig)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("hmacSecret")));
    }

    @Test
    void emptyBody_validHmac_stillLaunches() throws Exception {
        String body = "";
        String sig = hmac(SECRET, body);

        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .header("X-Signature", sig)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true));
    }
}
