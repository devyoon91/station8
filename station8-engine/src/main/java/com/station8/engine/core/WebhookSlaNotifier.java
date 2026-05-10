package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #138 — SLA webhook 발신자.
 *
 * <p>전역 URL ({@code engine.sla.webhook-url})과 인스턴스 단위 override({@link RunOptions#notificationWebhookUrl()})
 * 둘 중 더 좁은 범위 우선. 둘 다 없으면 콘솔 WARN 로그로 fallback.</p>
 *
 * <h3>페이로드</h3>
 * <pre>{@code
 * {
 *   "type": "SLA_VIOLATION",
 *   "instanceId": "...",
 *   "workflowName": "...",
 *   "startedAt": "2026-05-10T10:00:00",
 *   "elapsedSeconds": 3700,
 *   "thresholdSeconds": 3600,
 *   "action": "AUTO_TERMINATE"
 * }
 * }</pre>
 */
public class WebhookSlaNotifier implements SlaNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSlaNotifier.class);

    private final String webhookUrl;
    private final JsonUtil jsonUtil;
    private final HttpClient httpClient;

    public WebhookSlaNotifier(String webhookUrl, JsonUtil jsonUtil) {
        this.webhookUrl = webhookUrl;
        this.jsonUtil = jsonUtil;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void notify(SlaViolation violation, String overrideUrl) {
        String effectiveUrl = (overrideUrl != null && !overrideUrl.isBlank()) ? overrideUrl : webhookUrl;
        if (effectiveUrl == null || effectiveUrl.isBlank()) {
            log.warn("[SLA] webhook URL 미설정 — 콘솔 알림으로 대체. instance={}, workflow={}, elapsed={}s/{}s, action={}",
                    violation.instanceId(), violation.workflowName(),
                    violation.elapsedSeconds(), violation.thresholdSeconds(), violation.action());
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "SLA_VIOLATION");
            payload.put("instanceId", violation.instanceId());
            payload.put("workflowName", violation.workflowName());
            payload.put("startedAt", violation.startedAt() == null ? null : violation.startedAt().toString());
            payload.put("elapsedSeconds", violation.elapsedSeconds());
            payload.put("thresholdSeconds", violation.thresholdSeconds());
            payload.put("action", violation.action() == null ? null : violation.action().name());
            String body = jsonUtil.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean usingOverride = (overrideUrl != null && !overrideUrl.isBlank());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[SLA] 알림 발송 성공. instance={}, HTTP {}, override={}",
                        violation.instanceId(), response.statusCode(), usingOverride);
            } else {
                log.warn("[SLA] 알림 응답 비정상. instance={}, HTTP {}, body={}, override={}",
                        violation.instanceId(), response.statusCode(), response.body(), usingOverride);
            }
        } catch (Exception e) {
            log.error("[SLA] 알림 발송 실패. instance={}, URL={}", violation.instanceId(), effectiveUrl, e);
        }
    }
}
