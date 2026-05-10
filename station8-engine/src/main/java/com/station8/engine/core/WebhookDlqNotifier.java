package com.station8.engine.core;

import com.station8.engine.entity.DlqEntry;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * DlqNotifier의 웹훅 기반 기본 구현체.
 * DLQ 적재 시 설정된 URL로 JSON POST 요청을 발송합니다.
 * 웹훅 URL이 설정되지 않은 경우 콘솔 로그로 대체합니다.
 */
public class WebhookDlqNotifier implements DlqNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookDlqNotifier.class);

    private final String webhookUrl;
    private final JsonUtil jsonUtil;
    private final HttpClient httpClient;

    public WebhookDlqNotifier(String webhookUrl, JsonUtil jsonUtil) {
        this.webhookUrl = webhookUrl;
        this.jsonUtil = jsonUtil;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public void notify(DlqEntry entry) {
        notify(entry, null);
    }

    @Override
    public void notify(DlqEntry entry, String overrideUrl) {
        String effectiveUrl = (overrideUrl != null && !overrideUrl.isBlank()) ? overrideUrl : webhookUrl;
        if (effectiveUrl == null || effectiveUrl.isBlank()) {
            log.warn("[DLQ] 웹훅 URL 미설정 — 콘솔 알림으로 대체. DLQ ID={}, Activity={}, Line={}",
                entry.id(), entry.activityName(), entry.workflowName());
            return;
        }

        try {
            String body = jsonUtil.toJson(entry);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(effectiveUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean usingOverride = (overrideUrl != null && !overrideUrl.isBlank());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[DLQ] 웹훅 알림 발송 성공. DLQ ID={}, HTTP {}, override={}",
                        entry.id(), response.statusCode(), usingOverride);
            } else {
                log.warn("[DLQ] 웹훅 알림 응답 비정상. DLQ ID={}, HTTP {}, Body={}, override={}",
                        entry.id(), response.statusCode(), response.body(), usingOverride);
            }
        } catch (Exception e) {
            log.error("[DLQ] 웹훅 알림 발송 실패. DLQ ID={}, URL={}", entry.id(), effectiveUrl, e);
        }
    }
}
