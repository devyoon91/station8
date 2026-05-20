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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * #310 / #312 — WebhookTriggerController 회귀 가드.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>HMAC 검증 통과 (X-Timestamp 포함) → 라인 시작 + instanceId 응답</li>
 *   <li>잘못된 signature → 401</li>
 *   <li>존재 안 하는 key → 404</li>
 *   <li>비활성 trigger → 404</li>
 *   <li>signature 없음 → 401</li>
 *   <li>X-Timestamp 없음 → 400 (#312)</li>
 *   <li>X-Timestamp 윈도우 밖 → 401 (#312)</li>
 *   <li>vault에 hmacSecret credential 없음 → 500</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "station8.credential.key=FtvTfooEL5Ei04oVv5b9oMgTRxqtzn/rVN7GG7WOd80=",
        "station8.webhook.replay-window-seconds=300"
})
class WebhookTriggerControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LineDefinitionService definitionService;
    @Autowired LineTriggerRepository triggerRepository;
    @Autowired CredentialRepository credentialRepository;
    @Autowired CredentialCrypto crypto;
    @Autowired ObjectMapper objectMapper;
    @Autowired WebhookRateLimiter rateLimiter;
    @Autowired WebhookReplayGuard replayGuard;

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

        // #312 — replay/rate-limiter 상태 초기화 (테스트 간 격리)
        rateLimiter.reset();
        replayGuard.reset();

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

    /** HMAC-SHA256(timestamp + "\n" + body, secret) → hex. #312 replay defense payload. */
    private static String hmacWithTimestamp(String secret, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] payload = (timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private static String hmacBodyOnly(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String nowMillis() {
        return Long.toString(System.currentTimeMillis());
    }

    private MockHttpServletRequestBuilder signedPost(String body, String timestamp, String signature) {
        MockHttpServletRequestBuilder req = post("/api/triggers/webhook/" + triggerKey)
                .header("X-Signature", signature)
                .contentType("application/json")
                .content(body);
        if (timestamp != null) req = req.header("X-Timestamp", timestamp);
        return req;
    }

    @Test
    void validSignatureWithTimestamp_launchesInstance() throws Exception {
        String body = "{\"orderId\":\"42\"}";
        String ts = nowMillis();
        String sig = hmacWithTimestamp(SECRET, ts, body);

        mvc.perform(signedPost(body, ts, sig))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.definitionId").value(definitionId));
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        String body = "{\"orderId\":\"42\"}";
        String ts = nowMillis();
        String wrongSig = hmacWithTimestamp("different-secret", ts, body);

        mvc.perform(signedPost(body, ts, wrongSig))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid signature"));
    }

    @Test
    void missingSignature_returns401() throws Exception {
        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .header("X-Timestamp", nowMillis())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("X-Signature")));
    }

    @Test
    void missingTimestamp_returns400() throws Exception {
        String body = "{}";
        String sig = hmacBodyOnly(SECRET, body); // 어차피 signature 비교 전에 timestamp 거절됨
        mvc.perform(post("/api/triggers/webhook/" + triggerKey)
                        .header("X-Signature", sig)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("X-Timestamp")));
    }

    @Test
    void timestampOutOfWindow_returns401() throws Exception {
        String body = "{}";
        // 1시간 전 — 5분 window 밖
        String oldTs = Long.toString(System.currentTimeMillis() - 3600_000L);
        String sig = hmacWithTimestamp(SECRET, oldTs, body);
        mvc.perform(signedPost(body, oldTs, sig))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("window")));
    }

    @Test
    void timestampNonNumeric_returns400() throws Exception {
        String body = "{}";
        String sig = hmacWithTimestamp(SECRET, "not-a-number", body);
        mvc.perform(signedPost(body, "not-a-number", sig))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("epoch")));
    }

    @Test
    void replayedRequest_returns401() throws Exception {
        String body = "{\"orderId\":\"replay\"}";
        String ts = nowMillis();
        String sig = hmacWithTimestamp(SECRET, ts, body);

        // 1차 — 성공
        mvc.perform(signedPost(body, ts, sig))
                .andExpect(status().isOk());

        // 2차 — 같은 (key, ts, sig) → replay 거절
        mvc.perform(signedPost(body, ts, sig))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("replay detected"));
    }

    @Test
    void unknownKey_returns404() throws Exception {
        String ts = nowMillis();
        String sig = hmacWithTimestamp(SECRET, ts, "{}");
        mvc.perform(post("/api/triggers/webhook/no-such-key")
                        .header("X-Signature", sig)
                        .header("X-Timestamp", ts)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.key").value("no-such-key"));
    }

    @Test
    void inactiveTrigger_returns404() throws Exception {
        jdbcTemplate.update(
                "UPDATE U_LINE_TRIGGER SET ACTIVE_FL = 'N' WHERE TRIGGER_KEY = ?",
                triggerKey);
        String ts = nowMillis();
        String sig = hmacWithTimestamp(SECRET, ts, "{}");
        mvc.perform(signedPost("{}", ts, sig))
                .andExpect(status().isNotFound());
    }

    @Test
    void hmacSecretCredentialMissing_returns500() throws Exception {
        jdbcTemplate.update("DELETE FROM U_LINE_CREDENTIAL WHERE NAME = ?", CRED_NAME);
        String ts = nowMillis();
        String sig = hmacWithTimestamp(SECRET, ts, "{}");
        mvc.perform(signedPost("{}", ts, sig))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("hmacSecret")));
    }

    @Test
    void emptyBody_validHmac_stillLaunches() throws Exception {
        String body = "";
        String ts = nowMillis();
        String sig = hmacWithTimestamp(SECRET, ts, body);
        mvc.perform(signedPost(body, ts, sig))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true));
    }

    @Test
    void rateLimit_exceedsBurst_returns429() throws Exception {
        // trigger config 갱신 — rateLimit 추가
        String configJson = objectMapper.writeValueAsString(java.util.Map.of(
                "hmacSecret", CRED_NAME,
                "rateLimit", java.util.Map.of(
                        "maxPerMinute", 1,
                        "burstSize", 2)));
        jdbcTemplate.update(
                "UPDATE U_LINE_TRIGGER SET CONFIG_JSON = ? WHERE TRIGGER_KEY = ?",
                configJson, triggerKey);

        // 매 호출마다 body/timestamp 다르게 — replay dedup이 아니라 rate limit이 막는지 확인
        for (int i = 0; i < 2; i++) {
            String body = "{\"i\":" + i + "}";
            String ts = Long.toString(System.currentTimeMillis() + i);
            String sig = hmacWithTimestamp(SECRET, ts, body);
            mvc.perform(signedPost(body, ts, sig))
                    .andExpect(status().isOk());
        }

        // 3번째 — burst 소진, 429
        String body3 = "{\"i\":3}";
        String ts3 = Long.toString(System.currentTimeMillis() + 100);
        String sig3 = hmacWithTimestamp(SECRET, ts3, body3);
        mvc.perform(signedPost(body3, ts3, sig3))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("rate")));
    }
}
