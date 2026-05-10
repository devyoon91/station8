package com.station8.engine.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #177 — ConcurrencyStrategy 순수 단위 테스트 (Spring 없음).
 *
 * <p>Strategy는 Context에 주입된 supplier/predicate로만 인프라와 상호작용 — 본 테스트는
 * 메모리 stub으로 모든 시나리오를 빠르게 검증.</p>
 */
class ConcurrencyStrategyTest {

    @Test
    void parse_unknownString_returnsConcurrent() {
        assertThat(ConcurrencyStrategy.parse(null)).isInstanceOf(ConcurrencyStrategy.Concurrent.class);
        assertThat(ConcurrencyStrategy.parse("")).isInstanceOf(ConcurrencyStrategy.Concurrent.class);
        assertThat(ConcurrencyStrategy.parse("UNKNOWN")).isInstanceOf(ConcurrencyStrategy.Concurrent.class);
    }

    @Test
    void parse_caseInsensitive() {
        assertThat(ConcurrencyStrategy.parse("skip_if_running"))
                .isInstanceOf(ConcurrencyStrategy.SkipIfRunning.class);
        assertThat(ConcurrencyStrategy.parse(" Pipeline_2 "))
                .isInstanceOf(ConcurrencyStrategy.Pipeline.class);
    }

    @Test
    void parse_pipelineVariants() {
        assertThat(((ConcurrencyStrategy.Pipeline) ConcurrencyStrategy.parse("PIPELINE_1")).k()).isEqualTo(1);
        assertThat(((ConcurrencyStrategy.Pipeline) ConcurrencyStrategy.parse("PIPELINE_2")).k()).isEqualTo(2);
        assertThat(((ConcurrencyStrategy.Pipeline) ConcurrencyStrategy.parse("PIPELINE_3")).k()).isEqualTo(3);
    }

    @Test
    void pipeline_invalidK_throws() {
        assertThatThrownBy(() -> new ConcurrencyStrategy.Pipeline(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConcurrencyStrategy.Pipeline(4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyName_roundTrip() {
        assertThat(new ConcurrencyStrategy.Concurrent().policyName()).isEqualTo("CONCURRENT");
        assertThat(new ConcurrencyStrategy.SkipIfRunning().policyName()).isEqualTo("SKIP_IF_RUNNING");
        assertThat(new ConcurrencyStrategy.Pipeline(2).policyName()).isEqualTo("PIPELINE_2");
    }

    // ===== evaluateOnStart =====

    @Test
    void concurrent_evaluateOnStart_alwaysAllows() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.Concurrent();
        ConcurrencyStrategy.StartContext ctx = new ConcurrencyStrategy.StartContext("F", () -> "x");
        ConcurrencyStrategy.StartResult r = s.evaluateOnStart(ctx);
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void skipIfRunning_noActiveInstance_allows() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.SkipIfRunning();
        ConcurrencyStrategy.StartContext ctx = new ConcurrencyStrategy.StartContext("F", () -> null);
        ConcurrencyStrategy.StartResult r = s.evaluateOnStart(ctx);
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void skipIfRunning_activeInstanceExists_skipsWithReason() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.SkipIfRunning();
        ConcurrencyStrategy.StartContext ctx = new ConcurrencyStrategy.StartContext("MyFlow", () -> "inst-42");
        ConcurrencyStrategy.StartResult r = s.evaluateOnStart(ctx);
        assertThat(r.allowed()).isFalse();
        assertThat(r.conflictingInstanceId()).isEqualTo("inst-42");
        assertThat(r.reason()).contains("MyFlow").contains("inst-42").contains("SKIP_IF_RUNNING");
    }

    @Test
    void concurrent_evaluateOnStart_doesNotInvokeSupplier() {
        // 효과 검증: Concurrent는 SQL을 부르지 않아야 함 (lazy supplier)
        boolean[] called = {false};
        ConcurrencyStrategy s = new ConcurrencyStrategy.Concurrent();
        ConcurrencyStrategy.StartContext ctx = new ConcurrencyStrategy.StartContext("F", () -> {
            called[0] = true;
            return "x";
        });
        s.evaluateOnStart(ctx);
        assertThat(called[0]).as("Concurrent는 firstActiveInstanceId supplier 호출 안 해야 함").isFalse();
    }

    // ===== evaluateOnDispatch =====

    @Test
    void concurrent_evaluateOnDispatch_alwaysAllows() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.Concurrent();
        ConcurrencyStrategy.DispatchResult r = s.evaluateOnDispatch(stubDispatchCtx(0, List.of("p1")));
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void skipIfRunning_evaluateOnDispatch_alwaysAllows() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.SkipIfRunning();
        ConcurrencyStrategy.DispatchResult r = s.evaluateOnDispatch(stubDispatchCtx(0, List.of("p1")));
        assertThat(r.allowed()).isTrue();
    }

    @Test
    void pipeline1_priorSameNodeNotCompleted_blocks() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.Pipeline(1);
        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                "me", "n-a", "F", 0,
                step -> Set.of(),
                List.of("prior1"),
                (priorId, nodeId) -> false,           // not completed
                (priorId, nodes) -> false
        );
        ConcurrencyStrategy.DispatchResult r = s.evaluateOnDispatch(ctx);
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("PIPELINE_1");
    }

    @Test
    void pipeline1_priorSameNodeCompleted_allows() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.Pipeline(1);
        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                "me", "n-a", "F", 0,
                step -> Set.of(),
                List.of("prior1"),
                (priorId, nodeId) -> true,            // completed
                (priorId, nodes) -> false
        );
        assertThat(s.evaluateOnDispatch(ctx).allowed()).isTrue();
    }

    @Test
    void pipeline2_priorNotAtNextStep_blocks() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.Pipeline(2);
        Map<Integer, Set<String>> stepMap = Map.of(0, Set.of("n-a"), 1, Set.of("n-b"));
        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                "me", "n-a", "F", 0,
                step -> stepMap.getOrDefault(step, Set.of()),
                List.of("prior1"),
                (priorId, nodeId) -> false,
                (priorId, nodes) -> false              // not started any S+1 node
        );
        ConcurrencyStrategy.DispatchResult r = s.evaluateOnDispatch(ctx);
        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).contains("PIPELINE_2");
    }

    @Test
    void pipeline2_priorAtNextStep_allows() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.Pipeline(2);
        Map<Integer, Set<String>> stepMap = Map.of(0, Set.of("n-a"), 1, Set.of("n-b"));
        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                "me", "n-a", "F", 0,
                step -> stepMap.getOrDefault(step, Set.of()),
                List.of("prior1"),
                (priorId, nodeId) -> false,
                (priorId, nodes) -> nodes.contains("n-b")   // started B
        );
        assertThat(s.evaluateOnDispatch(ctx).allowed()).isTrue();
    }

    @Test
    void pipeline_lastNode_noNodesAtTargetStep_allows() {
        // S+gap 단계에 노드 없음 — 데드락 회피로 통과
        ConcurrencyStrategy s = new ConcurrencyStrategy.Pipeline(2);
        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                "me", "n-last", "F", 5,           // step 5
                step -> Set.of(),                  // 어떤 step도 노드 없음
                List.of("prior1"),
                (priorId, nodeId) -> false,
                (priorId, nodes) -> false
        );
        assertThat(s.evaluateOnDispatch(ctx).allowed()).isTrue();
    }

    @Test
    void pipeline_noPriorRunning_allows() {
        ConcurrencyStrategy s = new ConcurrencyStrategy.Pipeline(1);
        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                "me", "n-a", "F", 0,
                step -> Set.of(),
                List.of(),                          // 선행 0건
                (priorId, nodeId) -> false,
                (priorId, nodes) -> false
        );
        assertThat(s.evaluateOnDispatch(ctx).allowed()).isTrue();
    }

    @Test
    void pipeline_anyPriorBlocks_returnsBlocked() {
        // 선행 2건 중 1건이 통과 못 하면 차단
        ConcurrencyStrategy s = new ConcurrencyStrategy.Pipeline(1);
        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                "me", "n-a", "F", 0,
                step -> Set.of(),
                List.of("prior1", "prior2"),
                (priorId, nodeId) -> "prior1".equals(priorId),  // prior1 OK, prior2 NOT
                (priorId, nodes) -> false
        );
        assertThat(s.evaluateOnDispatch(ctx).allowed()).isFalse();
    }

    private static ConcurrencyStrategy.DispatchContext stubDispatchCtx(
            int myStep, List<String> priors) {
        return new ConcurrencyStrategy.DispatchContext(
                "me", "n-a", "F", myStep,
                step -> Set.of(),
                priors,
                (priorId, nodeId) -> false,
                (priorId, nodes) -> false
        );
    }
}
