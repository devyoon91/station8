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
 * @param slaSeconds             #138 — 인스턴스 단위 SLA 시간 임계치 override.
 *                               null이면 정의의 default ({@code U_LINE_DEFINITION.SLA_SECONDS}) 사용.
 * @param slaAction              #138 — SLA 위반 시 액션 override.
 *                               null이면 정의의 default 사용.
 */
public record RunOptions(
        OnFailure onFailure,
        Map<String, String> runtimeParams,
        String notificationWebhookUrl,
        Long slaSeconds,
        SlaAction slaAction
) {
    public RunOptions {
        if (onFailure == null) onFailure = OnFailure.CONTINUE;
        if (runtimeParams == null) runtimeParams = Collections.emptyMap();
    }

    /** 후방 호환 — 3-arg 생성자 (SLA 없음). */
    public RunOptions(OnFailure onFailure, Map<String, String> runtimeParams,
                      String notificationWebhookUrl) {
        this(onFailure, runtimeParams, notificationWebhookUrl, null, null);
    }

    /** 옵션 미설정 시 default 객체. continue + 빈 params + 전역 webhook + SLA 비활성. */
    public static RunOptions defaults() {
        return new RunOptions(OnFailure.CONTINUE, new LinkedHashMap<>(), null, null, null);
    }

    /**
     * {@code U_LINE_INSTANCE.RUN_OPTIONS} CLOB JSON에서 파싱. null/빈 문자열이면 {@link #defaults()}.
     * 알 수 없는 필드는 무시 (후방 호환).
     *
     * @deprecated 신규 코드는 {@link RunOptionsCodec#parseFromClob(String)} 사용 — Spring bean 단일 진입점.
     *             본 정적 메서드는 테스트 후방 호환을 위해 유지.
     */
    @Deprecated
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

        // #138 — slaSeconds (Number 또는 String 모두 허용)
        Long slaSeconds = null;
        Object slaRaw = raw.get("slaSeconds");
        if (slaRaw instanceof Number sn) {
            slaSeconds = sn.longValue();
        } else if (slaRaw instanceof String ss && !ss.isBlank()) {
            try { slaSeconds = Long.parseLong(ss.trim()); } catch (NumberFormatException ignore) {}
        }
        SlaAction slaAction = null;
        Object slaActRaw = raw.get("slaAction");
        if (slaActRaw instanceof String sas && !sas.isBlank()) {
            slaAction = SlaAction.parse(sas);
        }

        return new RunOptions(onFailure, params, webhook, slaSeconds, slaAction);
    }

    public enum OnFailure {
        /** 활동 FAILED 시 retry → DLQ. 인스턴스 다른 활동은 계속 (기존 동작). */
        CONTINUE,
        /** 활동 FAILED 시 인스턴스 즉시 TERMINATED. 진행 중 RUNNING은 워커 자연 완료 후 fan-out 차단 (#101). */
        ABORT,
        /**
         * #148 — 활동 FAILED 시 인스턴스를 PAUSED로 마킹. 운영자 개입 대기.
         *
         * <p>{@link LineExecutor#pauseLine}을 통해 인스턴스만 PAUSED로 전이.
         * 실패한 활동은 FAILED 상태 그대로 남아있어 운영자가 timeline에서:</p>
         * <ul>
         *   <li>Unpause → 인스턴스 RUNNING → FAILED 활동 옆 ↻ Retry 클릭 (#139) — 그 활동 1건만 재시도</li>
         *   <li>또는 Terminate — 인스턴스 강제 종료</li>
         * </ul>
         *
         * <p>의존: #139 PAUSED 상태 인프라 (워커 폴링이 PAUSED 인스턴스 활동 차단 + Unpause 시 fan-out 재평가).</p>
         */
        PAUSE_ON_FAILURE;

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
