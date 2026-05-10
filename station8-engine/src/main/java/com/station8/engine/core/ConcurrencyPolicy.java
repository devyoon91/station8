package com.station8.engine.core;

/**
 * #141, #164 — 라인 정의 단위 동시 실행 정책.
 *
 * <ul>
 *   <li>{@link #CONCURRENT} (기본) — 같은 정의의 인스턴스가 동시에 여러 개 실행 가능 (기존 동작)</li>
 *   <li>{@link #SKIP_IF_RUNNING} (#141) — 같은 정의의 RUNNING 또는 PAUSED 인스턴스가 있으면 새 인스턴스 시작 안 함
 *       (cron 적체 방지). 호출자에겐 200 + {@code skipped:true}로 응답.</li>
 *   <li>{@link #PIPELINE_1}, {@link #PIPELINE_2}, {@link #PIPELINE_3} (#164) —
 *       동시 실행을 허용하되 활동 단위로 단계 동기화. 선행 인스턴스가 충분히 앞서야
 *       후행 인스턴스가 그 단계 진입 가능 (Azkaban Pipeline 1/2/3 벤치마킹).</li>
 * </ul>
 *
 * <h3>Pipeline 게이트 규칙 (#164)</h3>
 * <p>새 인스턴스의 노드 N(위상 단계 S)이 RUNNING으로 진입할 수 있는 조건 — 선행 RUNNING 인스턴스 모두에 대해:</p>
 * <ul>
 *   <li>{@code PIPELINE_1} — 선행 인스턴스의 같은 노드 N이 COMPLETED.</li>
 *   <li>{@code PIPELINE_2} — 선행 인스턴스가 단계 {@code S+1}의 어떤 노드든 STARTED (RUNNING/COMPLETED).</li>
 *   <li>{@code PIPELINE_3} — 선행 인스턴스가 단계 {@code S+2}의 어떤 노드든 STARTED.</li>
 * </ul>
 * <p>선행 RUNNING 후보가 0건이거나 단계 {@code S+(K-1)}에 노드가 존재하지 않으면 게이트가 통과 — 곧 인스턴스 끝.</p>
 *
 * <p>구현은 {@code PipelineGate}; 위상 단계는 {@code LineDagTopo} (Kahn BFS).</p>
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

    /** Pipeline 모드의 게이트 단계 차이 (K-1). PIPELINE_1=0, PIPELINE_2=1, PIPELINE_3=2. */
    public int pipelineGap() {
        return switch (this) {
            case PIPELINE_1 -> 0;
            case PIPELINE_2 -> 1;
            case PIPELINE_3 -> 2;
            default -> -1;  // 의미 없음
        };
    }

    /** Pipeline 모드인지 (PIPELINE_1/2/3 중 하나). */
    public boolean isPipeline() {
        return this == PIPELINE_1 || this == PIPELINE_2 || this == PIPELINE_3;
    }
}
