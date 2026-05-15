package com.station8.engine.core;

import com.station8.engine.entity.LineStation;
import com.station8.engine.entity.LineTrack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #164 — LineDagTopo Kahn BFS 검증.
 */
class LineDagTopoTest {

    @Test
    void linearDag_assignsIncreasingSteps() {
        // A → B → C → D
        List<LineStation> nodes = List.of(
                node("a"), node("b"), node("c"), node("d"));
        List<LineTrack> edges = List.of(
                edge("a", "b"), edge("b", "c"), edge("c", "d"));

        Map<String, Integer> steps = LineDagTopo.computeStepLayers(nodes, edges);

        assertThat(steps).containsEntry("a", 0)
                .containsEntry("b", 1)
                .containsEntry("c", 2)
                .containsEntry("d", 3);
        assertThat(LineDagTopo.maxStep(steps)).isEqualTo(3);
    }

    @Test
    void diamondDag_assignsMaxStepFromAnyPredecessor() {
        // A → B → D, A → C → D — D는 max(B,C) + 1 = 2
        List<LineStation> nodes = List.of(node("a"), node("b"), node("c"), node("d"));
        List<LineTrack> edges = List.of(
                edge("a", "b"), edge("a", "c"),
                edge("b", "d"), edge("c", "d"));

        Map<String, Integer> steps = LineDagTopo.computeStepLayers(nodes, edges);

        assertThat(steps).containsEntry("a", 0)
                .containsEntry("b", 1)
                .containsEntry("c", 1)
                .containsEntry("d", 2);
    }

    @Test
    void unevenDiamond_takesLongestPath() {
        // A → B → C → E, A → D → E — E는 max(C, D) + 1 = max(2, 1) + 1 = 3
        List<LineStation> nodes = List.of(node("a"), node("b"), node("c"), node("d"), node("e"));
        List<LineTrack> edges = List.of(
                edge("a", "b"), edge("b", "c"), edge("c", "e"),
                edge("a", "d"), edge("d", "e"));

        Map<String, Integer> steps = LineDagTopo.computeStepLayers(nodes, edges);

        assertThat(steps).containsEntry("a", 0)
                .containsEntry("b", 1)
                .containsEntry("c", 2)
                .containsEntry("d", 1)
                .containsEntry("e", 3);
    }

    @Test
    void cycle_unreachableNodesExcluded() {
        // A → B, B → C, C → B (cycle B↔C) — 사이클 노드는 step 부여 안 됨
        List<LineStation> nodes = List.of(node("a"), node("b"), node("c"));
        List<LineTrack> edges = List.of(
                edge("a", "b"), edge("b", "c"), edge("c", "b"));

        Map<String, Integer> steps = LineDagTopo.computeStepLayers(nodes, edges);

        assertThat(steps).containsKey("a");
        // B는 in-degree 2 (A + C) — A 처리 후 1로 떨어짐, 사이클이라 0 도달 못 함
        assertThat(steps).doesNotContainKey("b");
        assertThat(steps).doesNotContainKey("c");
    }

    @Test
    void parallelStartNodes_bothAtZero() {
        // A, B 둘 다 시작 노드 (in-degree 0)
        List<LineStation> nodes = List.of(node("a"), node("b"), node("c"));
        List<LineTrack> edges = List.of(edge("a", "c"), edge("b", "c"));

        Map<String, Integer> steps = LineDagTopo.computeStepLayers(nodes, edges);

        assertThat(steps).containsEntry("a", 0)
                .containsEntry("b", 0)
                .containsEntry("c", 1);
    }

    @Test
    void nodesAtStep_returnsAllAtThatStep() {
        Map<String, Integer> steps = Map.of("a", 0, "b", 1, "c", 1, "d", 2);
        Set<String> step1 = LineDagTopo.nodesAtStep(steps, 1);
        assertThat(step1).containsExactlyInAnyOrder("b", "c");
        Set<String> step5 = LineDagTopo.nodesAtStep(steps, 5);
        assertThat(step5).isEmpty();
    }

    @Test
    void emptyGraph_returnsEmptyMap() {
        Map<String, Integer> steps = LineDagTopo.computeStepLayers(List.of(), List.of());
        assertThat(steps).isEmpty();
        assertThat(LineDagTopo.maxStep(steps)).isEqualTo(-1);
    }

    private static LineStation node(String id) {
        return new LineStation(id, "def", id, "A",
                /*inputParams*/ null, /*datasourceBindings*/ null,
                /*posXNo*/ 0, /*posYNo*/ 0,
                "N",
                null, null, null, null);
    }

    private static LineTrack edge(String from, String to) {
        return new LineTrack("e-" + from + "-" + to, "def", from, to,
                /*conditionExpr*/ null,
                "N",
                null, null, null, null);
    }
}
