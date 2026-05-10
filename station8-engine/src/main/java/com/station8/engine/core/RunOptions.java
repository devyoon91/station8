package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인스턴스 단위 실행 옵션 (#134).
 *
 * <p>사용자가 즉시 실행 시점에 전달하거나, cron 스케줄에 정해진 옵션을 가지고 시작.
 * {@code U_LINE_INSTANCE.RUN_OPTIONS} CLOB에 JSON으로 영속화된다.</p>
 *
 * @param onFailure              {@link OnFailure#CONTINUE} 또는 {@link OnFailure#ABORT}.
 *                               default {@code CONTINUE} (기존 동작 유지 — 후방 호환).
 * @param runtimeParams          액티비티가 {@code LineContext.runtimeParams()}로 접근하는 임시 맵 (D7).
 *                               null이면 빈 맵.
 * @param notificationWebhookUrl 인스턴스 단위 DLQ webhook override (D8). null이면 전역
 *                               {@code engine.dlq.webhook-url} 사용.
 */
public record RunOptions(
        OnFailure onFailure,
        Map<String, String> runtimeParams,
        String notificationWebhookUrl
) {
    public RunOptions {
        if (onFailure == null) onFailure = OnFailure.CONTINUE;
        if (runtimeParams == null) runtimeParams = Collections.emptyMap();
    }

    /** 옵션 미설정 시 default 객체. continue + 빈 params + 전역 webhook. */
    public static RunOptions defaults() {
        return new RunOptions(OnFailure.CONTINUE, new LinkedHashMap<>(), null);
    }

    /**
     * {@code U_LINE_INSTANCE.RUN_OPTIONS} CLOB JSON에서 파싱. null/빈 문자열이면 {@link #defaults()}.
     * 알 수 없는 필드는 무시 (후방 호환).
     */
    @SuppressWarnings("unchecked")
    public static RunOptions parse(String json, JsonUtil jsonUtil) {
        if (json == null || json.isBlank()) return defaults();
        Map<String, Object> raw = (Map<String, Object>) jsonUtil.fromJson(json, Map.class);
        if (raw == null) return defaults();

        OnFailure onFailure = OnFailure.parse((String) raw.get("onFailure"));
        Map<String, String> params = new LinkedHashMap<>();
        Object pRaw = raw.get("runtimeParams");
        if (pRaw instanceof Map<?, ?> pm) {
            pm.forEach((k, v) -> params.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        String webhook = (String) raw.get("notificationWebhookUrl");
        return new RunOptions(onFailure, params, webhook);
    }

    public enum OnFailure {
        /** 활동 FAILED 시 retry → DLQ. 인스턴스 다른 활동은 계속 (기존 동작). */
        CONTINUE,
        /** 활동 FAILED 시 인스턴스 즉시 TERMINATED. 진행 중 RUNNING은 워커 자연 완료 후 fan-out 차단 (#101). */
        ABORT;

        public static OnFailure parse(String s) {
            if (s == null || s.isBlank()) return CONTINUE;
            try {
                return OnFailure.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return CONTINUE;
            }
        }
    }
}
