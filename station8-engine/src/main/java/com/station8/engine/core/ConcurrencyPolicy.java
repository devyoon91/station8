package com.station8.engine.core;

/**
 * #141, #164 — 라인 정의 단위 동시 실행 정책의 DB 직렬화 키.
 *
 * <p>실제 정책 동작은 {@link ConcurrencyStrategy} (sealed interface)에 위임 — #177.
 * 본 enum은 DB 컬럼 값 ↔ 이름 매핑만 담당.</p>
 *
 * <ul>
 *   <li>{@link #CONCURRENT} (기본) — 동시 실행 허용</li>
 *   <li>{@link #SKIP_IF_RUNNING} (#141) — 활성 인스턴스 있으면 새 시작 거부</li>
 *   <li>{@link #PIPELINE_1}/{@link #PIPELINE_2}/{@link #PIPELINE_3} (#164) — 단계 동기화</li>
 * </ul>
 *
 * @see ConcurrencyStrategy
 */
public enum ConcurrencyPolicy {
    CONCURRENT,
    SKIP_IF_RUNNING,
    PIPELINE_1,
    PIPELINE_2,
    PIPELINE_3;

    /** null/blank/잘못된 값은 {@link #CONCURRENT} (기본 후방 호환). */
    public static ConcurrencyPolicy parse(String s) {
        if (s == null || s.isBlank()) return CONCURRENT;
        try {
            return ConcurrencyPolicy.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CONCURRENT;
        }
    }

    /** 본 enum 값에 대응하는 {@link ConcurrencyStrategy} 인스턴스. */
    public ConcurrencyStrategy toStrategy() {
        return ConcurrencyStrategy.parse(name());
    }
}
