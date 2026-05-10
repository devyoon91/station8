package com.station8.engine.core;

/**
 * #141 — 라인 정의 단위 동시 실행 정책.
 *
 * <ul>
 *   <li>{@link #CONCURRENT} (기본) — 같은 정의의 인스턴스가 동시에 여러 개 실행 가능 (기존 동작)</li>
 *   <li>{@link #SKIP_IF_RUNNING} — 같은 정의의 RUNNING 또는 PAUSED 인스턴스가 있으면 새 인스턴스 시작 안 함
 *       (cron 적체 방지). 호출자에겐 200 + {@code skipped:true}로 응답.</li>
 * </ul>
 *
 * <p>{@code Pipeline 1/2/3} 모드는 별도 follow-up 이슈로 분리.</p>
 */
public enum ConcurrencyPolicy {
    CONCURRENT,
    SKIP_IF_RUNNING;

    /** null/blank/잘못된 값은 {@link #CONCURRENT} (기본 후방 호환). */
    public static ConcurrencyPolicy parse(String s) {
        if (s == null || s.isBlank()) return CONCURRENT;
        try {
            return ConcurrencyPolicy.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CONCURRENT;
        }
    }
}
