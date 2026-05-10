package com.station8.engine.core;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * #177 — Concurrency 정책의 Strategy 추상화.
 *
 * <p>{@link ConcurrencyPolicy} enum이 DB/직렬화 키 역할이라면, 본 sealed interface는
 * 정책별 실제 동작을 다형성으로 분리한다 — `if (policy == X)` 분기를 제거.
 * 새 정책 추가 = 새 record 1개 + {@link #parse(String)} 한 줄 (OCP).</p>
 *
 * <h3>두 게이트 시점</h3>
 * <ul>
 *   <li>{@link #evaluateOnStart} — 인스턴스 시작 시점.
 *       {@link SkipIfRunning}이 사용 ({@code LineScheduler}, {@code LineDefinitionService.runDefinitionWithResult}).</li>
 *   <li>{@link #evaluateOnDispatch} — 활동 dispatch 시점.
 *       {@link Pipeline}이 사용 ({@code PipelineGate}).</li>
 * </ul>
 * <p>각 구현은 자신과 무관한 시점에는 default {@code allow()}를 반환.</p>
 *
 * <h3>의존성 주입 방식</h3>
 * <p>구현 record는 immutable value object — Spring bean 아님.
 * 실제 DB lookup이 필요한 정보는 호출자(PipelineGate/Service/Scheduler)가 Context에
 * Supplier/Predicate로 주입. → 구현은 순수 로직, 단위 테스트 쉬움.</p>
 */
public sealed interface ConcurrencyStrategy {

    /** 시작 시점 게이트. */
    default StartResult evaluateOnStart(StartContext ctx) {
        return StartResult.allow();
    }

    /** Dispatch 시점 게이트. */
    default DispatchResult evaluateOnDispatch(DispatchContext ctx) {
        return DispatchResult.allow();
    }

    /** DB 직렬화 키 ({@link ConcurrencyPolicy} enum 이름과 동일). */
    String policyName();

    /**
     * DB 컬럼 값 → 전략 객체. null/blank/모르는 값은 {@link Concurrent}.
     */
    static ConcurrencyStrategy parse(String s) {
        if (s == null || s.isBlank()) return new Concurrent();
        return switch (s.trim().toUpperCase()) {
            case "CONCURRENT" -> new Concurrent();
            case "SKIP_IF_RUNNING" -> new SkipIfRunning();
            case "PIPELINE_1" -> new Pipeline(1);
            case "PIPELINE_2" -> new Pipeline(2);
            case "PIPELINE_3" -> new Pipeline(3);
            default -> new Concurrent();
        };
    }

    // ===== 구현 =====

    /** 기본 — 어떤 시점에도 차단 안 함. */
    record Concurrent() implements ConcurrencyStrategy {
        @Override public String policyName() { return "CONCURRENT"; }
    }

    /** 같은 정의의 RUNNING/PAUSED 인스턴스 있으면 새 시작 거부 (#141). */
    record SkipIfRunning() implements ConcurrencyStrategy {
        @Override public String policyName() { return "SKIP_IF_RUNNING"; }

        @Override
        public StartResult evaluateOnStart(StartContext ctx) {
            String conflicting = ctx.firstActiveInstanceId().get();  // lazy SQL — CONCURRENT는 호출 안 됨
            if (conflicting != null) {
                return StartResult.skip(
                        "Definition '" + ctx.workflowName() + "' has an active instance ("
                                + conflicting + ") with policy SKIP_IF_RUNNING",
                        conflicting);
            }
            return StartResult.allow();
        }
    }

    /**
     * Pipeline 모드 (#164) — 동시 실행 허용하되 단계 동기화.
     *
     * <ul>
     *   <li>k=1: 새 인스턴스의 노드 N은 모든 선행이 같은 N을 COMPLETED 했을 때만 시작.</li>
     *   <li>k=2: 새 N은 선행이 단계 (S+1)의 어떤 노드든 STARTED 했을 때만 시작.</li>
     *   <li>k=3: 단계 (S+2) STARTED.</li>
     * </ul>
     */
    record Pipeline(int k) implements ConcurrencyStrategy {
        public Pipeline {
            if (k < 1 || k > 3) {
                throw new IllegalArgumentException("Pipeline k는 1..3 — 입력=" + k);
            }
        }

        @Override public String policyName() { return "PIPELINE_" + k; }

        /** 단계 차이 (k-1). PIPELINE_1=0, PIPELINE_2=1, PIPELINE_3=2. */
        public int gap() { return k - 1; }

        @Override
        public DispatchResult evaluateOnDispatch(DispatchContext ctx) {
            // 선행 RUNNING 0건 — 통과
            if (ctx.priorInstanceIds().isEmpty()) return DispatchResult.allow();

            if (k == 1) {
                // 모든 선행에서 같은 노드가 COMPLETED 되어야 함
                for (String prior : ctx.priorInstanceIds()) {
                    if (!ctx.isNodeCompletedInPrior().test(prior, ctx.nodeId())) {
                        return DispatchResult.block(
                                "PIPELINE_1 — 선행 " + prior + "의 같은 노드가 아직 COMPLETED 아님");
                    }
                }
                return DispatchResult.allow();
            }

            // k=2,3: 단계 myStep+gap의 노드 중 하나라도 STARTED 되어야 함
            int targetStep = ctx.myStep() + gap();
            Set<String> targets = ctx.nodesAtStep().apply(targetStep);
            if (targets.isEmpty()) {
                // 파이프라인 끝 — 데드락 회피
                return DispatchResult.allow();
            }
            for (String prior : ctx.priorInstanceIds()) {
                if (!ctx.isAnyNodeStartedInPrior().test(prior, targets)) {
                    return DispatchResult.block(
                            "PIPELINE_" + k + " — 선행 " + prior + "이 단계 " + targetStep + "에 도달 못 함");
                }
            }
            return DispatchResult.allow();
        }
    }

    // ===== Context + Result records =====

    /**
     * 시작 시점 context.
     *
     * @param workflowName            정의 이름
     * @param firstActiveInstanceId   동일 workflow의 활성 인스턴스 1건 ID lazy supplier (없으면 null 반환).
     *                                Concurrent는 호출 안 함 — 불필요한 SQL 회피.
     */
    record StartContext(String workflowName, Supplier<String> firstActiveInstanceId) {}

    /**
     * Dispatch 시점 context.
     *
     * @param instanceId                 본인 인스턴스
     * @param nodeId                     dispatch 대상 노드
     * @param workflowName               워크플로 이름
     * @param myStep                     본 노드의 위상 단계 (LineDagTopo)
     * @param nodesAtStep                step → 그 단계 노드 ID 집합 lookup
     * @param priorInstanceIds           동일 workflow의 선행 RUNNING 인스턴스 ID 목록 (자기 제외)
     * @param isNodeCompletedInPrior     (priorId, nodeId) → 해당 노드 COMPLETED?
     * @param isAnyNodeStartedInPrior    (priorId, targets) → 노드 집합 중 하나라도 STARTED?
     */
    record DispatchContext(
            String instanceId,
            String nodeId,
            String workflowName,
            int myStep,
            IntFunction<Set<String>> nodesAtStep,
            List<String> priorInstanceIds,
            BiPredicate<String, String> isNodeCompletedInPrior,
            BiPredicate<String, Collection<String>> isAnyNodeStartedInPrior
    ) {}

    record StartResult(boolean allowed, String reason, String conflictingInstanceId) {
        public static StartResult allow() { return new StartResult(true, null, null); }
        public static StartResult skip(String reason, String conflictingId) {
            return new StartResult(false, reason, conflictingId);
        }
    }

    record DispatchResult(boolean allowed, String reason) {
        public static DispatchResult allow() { return new DispatchResult(true, null); }
        public static DispatchResult block(String reason) { return new DispatchResult(false, reason); }
    }
}
