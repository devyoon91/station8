package com.station8.engine.core;

/**
 * #138 — SLA 위반 시 적용할 액션.
 *
 * <ul>
 *   <li>{@link #ALERT_ONLY} (기본) — 위반 알림만 발송, 인스턴스는 그대로 진행</li>
 *   <li>{@link #AUTO_TERMINATE} — 위반 알림 + 인스턴스를 {@code TERMINATED}로 마킹</li>
 * </ul>
 *
 * <p>두 액션 모두 alert는 항상 발송된다 — 차이는 후속 처리 여부.</p>
 */
public enum SlaAction {
    ALERT_ONLY,
    AUTO_TERMINATE;

    /**
     * 문자열을 SlaAction으로 파싱. null/blank/알 수 없는 값은 {@link #ALERT_ONLY}.
     */
    public static SlaAction parse(String s) {
        if (s == null || s.isBlank()) return ALERT_ONLY;
        try {
            return SlaAction.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ALERT_ONLY;
        }
    }
}
