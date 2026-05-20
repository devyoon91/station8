package com.station8.app.triggers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.TriggerLauncher;
import com.station8.engine.entity.LineTrigger;
import com.station8.engine.repository.LineTriggerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * M20 (#310, #312) — webhook trigger endpoint.
 *
 * <p>외부 시스템이 {@code POST /api/triggers/webhook/{key}} 를 호출하면 해당 key의
 * {@link LineTrigger} 설정을 보고 HMAC 검증 후 {@link TriggerLauncher#launch}로 라인 시작.</p>
 *
 * <h3>인증</h3>
 * 본 endpoint 자체는 permitAll — HMAC이 인증 역할. 일반 사용자 로그인 안 거침 (외부 시스템 호출용).
 * SecurityConfig의 {@code /api/**} permitAll + CSRF 면제가 그대로 적용.
 *
 * <h3>플로우</h3>
 * <ol>
 *   <li>key로 trigger 조회. 없거나 비활성/삭제이면 404</li>
 *   <li>{@code triggerType != "webhook"} 이면 404 (잘못된 endpoint 호출)</li>
 *   <li>configJson 파싱 — {@code hmacSecret} 필수, {@code rateLimit} 선택 (#312)</li>
 *   <li>{@code allowedMethods}에 POST 있는지 — 없으면 405</li>
 *   <li>rate limit 통과 (#312) — 초과 시 429</li>
 *   <li>replay defense 활성 시 (#312):
 *     <ul>
 *       <li>{@code X-Timestamp} 헤더 필수 (epoch millis)</li>
 *       <li>현재 시각 ± {@code station8.webhook.replay-window-seconds} 안이어야 통과</li>
 *       <li>HMAC payload = {@code timestamp + "\n" + body}</li>
 *       <li>같은 (key, timestamp, signature) 튜플은 window 내 1회만 통과</li>
 *     </ul>
 *   </li>
 *   <li>{@code X-Signature} 헤더 검증 — HMAC-SHA256(payload, secret), hex encoding</li>
 *   <li>body를 inputData로 {@link TriggerLauncher#launch} 호출</li>
 *   <li>응답 {@code { instanceId, started, definitionId }}</li>
 * </ol>
 *
 * <h3>HMAC 계산</h3>
 * <p>replay defense 활성 (default): {@code HMAC-SHA256(timestamp + "\n" + body, secret)}, hex.<br>
 * 비활성 (legacy): {@code HMAC-SHA256(body, secret)}, hex.</p>
 *
 * <p>secret은 vault credential ({@code webhook_hmac} type) 에서 lookup — config에는
 * credential 이름만 박힘. URI/config에 평문 secret 절대 없음.</p>
 */
@RestController
public class WebhookTriggerController {

    private static final Logger log = LoggerFactory.getLogger(WebhookTriggerController.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final LineTriggerRepository triggerRepository;
    private final CredentialResolver credentialResolver;
    private final TriggerLauncher triggerLauncher;
    private final ObjectMapper objectMapper;
    private final WebhookRateLimiter rateLimiter;
    private final WebhookReplayGuard replayGuard;

    public WebhookTriggerController(LineTriggerRepository triggerRepository,
                                    CredentialResolver credentialResolver,
                                    TriggerLauncher triggerLauncher,
                                    ObjectMapper objectMapper,
                                    WebhookRateLimiter rateLimiter,
                                    WebhookReplayGuard replayGuard) {
        this.triggerRepository = triggerRepository;
        this.credentialResolver = credentialResolver;
        this.triggerLauncher = triggerLauncher;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.replayGuard = replayGuard;
    }

    @PostMapping(value = "/api/triggers/webhook/{key}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<?> trigger(@PathVariable("key") String key,
                                     @RequestHeader(value = "X-Signature", required = false) String signature,
                                     @RequestHeader(value = "X-Timestamp", required = false) String timestampHeader,
                                     @RequestBody(required = false) byte[] body) {
        LineTrigger trigger = triggerRepository.findByKey(key);
        if (trigger == null || !trigger.isActive() || !"webhook".equals(trigger.triggerType())) {
            return notFound(key);
        }

        WebhookConfig config;
        try {
            config = parseConfig(trigger.configJson());
        } catch (Exception ex) {
            log.warn("webhook trigger '{}' config 파싱 실패: {}", key, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "trigger config malformed"));
        }

        if (config.allowedMethods() != null && !config.allowedMethods().isEmpty()
                && !config.allowedMethods().contains("POST")) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                    Map.of("error", "method not allowed", "allowed", config.allowedMethods()));
        }

        // Rate limit — config에 rateLimit 있으면 적용
        RateLimit rl = config.rateLimit();
        if (rl != null && !rateLimiter.tryAcquire(key, rl.maxPerMinute(), rl.burstSize())) {
            log.info("webhook trigger '{}' rate limit 초과 — 429", key);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                    Map.of("error", "rate limit exceeded",
                            "maxPerMinute", rl.maxPerMinute()));
        }

        if (signature == null || signature.isBlank()) {
            return unauthorized("X-Signature header required");
        }

        // Replay defense — 활성 시 timestamp 헤더 검증
        if (replayGuard.isEnabled()) {
            WebhookReplayGuard.TimestampCheck tsCheck = replayGuard.checkTimestamp(timestampHeader);
            switch (tsCheck) {
                case MISSING -> {
                    return badRequest("X-Timestamp header required");
                }
                case INVALID -> {
                    return badRequest("X-Timestamp must be epoch millis");
                }
                case OUT_OF_WINDOW -> {
                    return unauthorized("X-Timestamp outside replay window");
                }
                case OK -> { /* proceed */ }
            }
        }

        String secret = lookupSecret(config.hmacSecret());
        if (secret == null) {
            log.warn("webhook trigger '{}' hmacSecret '{}' not in vault", key, config.hmacSecret());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "hmacSecret credential not found"));
        }
        byte[] effectiveBody = body == null ? new byte[0] : body;
        byte[] hmacPayload = replayGuard.isEnabled()
                ? buildTimestampedPayload(timestampHeader, effectiveBody)
                : effectiveBody;
        String expected = computeHmac(hmacPayload, secret);
        if (!constantTimeEquals(expected, signature)) {
            return unauthorized("invalid signature");
        }

        // Replay dedup — signature 검증 이후 (외부에서 보낸 random signature로 cache 오염 방지)
        if (replayGuard.isEnabled()
                && !replayGuard.recordOnce(key, timestampHeader, signature)) {
            return unauthorized("replay detected");
        }

        // 라인 시작 — body를 inputData로
        String inputData = new String(effectiveBody, StandardCharsets.UTF_8);
        TriggerLauncher.LaunchResult result;
        try {
            result = triggerLauncher.launch(trigger.definitionId(), inputData,
                    "Webhook:" + key);
        } catch (Exception ex) {
            log.error("webhook trigger '{}' launch 실패", key, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "launch failed: " + ex.getMessage()));
        }

        if (!result.started()) {
            return ResponseEntity.accepted().body(Map.of(
                    "started", false,
                    "definitionId", trigger.definitionId(),
                    "skipReason", result.skipReasonPolicy(),
                    "conflictingInstanceId", result.conflictingInstanceId()));
        }

        return ResponseEntity.ok(Map.of(
                "started", true,
                "instanceId", result.instanceId(),
                "definitionId", trigger.definitionId(),
                "workflowName", result.workflowName()));
    }

    private static byte[] buildTimestampedPayload(String timestamp, byte[] body) {
        byte[] prefix = (timestamp + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(body, 0, combined, prefix.length, body.length);
        return combined;
    }

    /** config는 schemaJson과 동일한 평문 JSON. {@code hmacSecret} 필수, {@code rateLimit} 선택. */
    private WebhookConfig parseConfig(String configJson) throws Exception {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("webhook config is empty");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(configJson, Map.class);
        Object secret = parsed.get("hmacSecret");
        if (secret == null) {
            throw new IllegalArgumentException("hmacSecret is required");
        }
        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) parsed.get("allowedMethods");
        RateLimit rl = parseRateLimit(parsed.get("rateLimit"));
        return new WebhookConfig(secret.toString(), methods, rl);
    }

    @SuppressWarnings("unchecked")
    private static RateLimit parseRateLimit(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object max = m.get("maxPerMinute");
        Object burst = m.get("burstSize");
        if (!(max instanceof Number maxNum)) return null;
        int maxPerMinute = maxNum.intValue();
        int burstSize = burst instanceof Number burstNum ? burstNum.intValue() : 0;
        return new RateLimit(maxPerMinute, burstSize);
    }

    /** vault에서 webhook_hmac credential lookup. 평문 secret 반환. 없으면 null. */
    private String lookupSecret(String credentialName) {
        CredentialResolver.Resolved cred = credentialResolver.resolveByName(credentialName);
        if (cred == null) return null;
        if (!"webhook_hmac".equals(cred.type())) {
            log.warn("credential '{}' is type {}, expected webhook_hmac", credentialName, cred.type());
            return null;
        }
        return cred.value();
    }

    /** HMAC-SHA256(payload, secret) → hex string. */
    private static String computeHmac(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new RuntimeException("HMAC computation failed", ex);
        }
    }

    /** Timing-attack 방어용 constant-time 비교. */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String key) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Map.of("error", "webhook trigger not found or inactive", "key", key));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized(String reason) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Map.of("error", reason));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String reason) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                Map.of("error", reason));
    }

    /**
     * webhook trigger config.
     *
     * @param hmacSecret      vault credential 이름 (type=webhook_hmac)
     * @param allowedMethods  허용 HTTP method 화이트리스트 (null/empty면 POST 허용)
     * @param rateLimit       per-trigger rate limit (null이면 비활성)
     */
    private record WebhookConfig(String hmacSecret, List<String> allowedMethods, RateLimit rateLimit) {}

    /**
     * @param maxPerMinute 분당 허용 호출 수. &lt;= 0 이면 비활성
     * @param burstSize    버킷 capacity. &lt;= 0 이면 maxPerMinute로 대체
     */
    private record RateLimit(int maxPerMinute, int burstSize) {}
}
