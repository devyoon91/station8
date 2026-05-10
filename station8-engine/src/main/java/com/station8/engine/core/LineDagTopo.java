package com.station8.engine.core;

import com.station8.engine.entity.LineStation;
import com.station8.engine.entity.LineTrack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * #164 — DAG의 노드별 위상 단계(topological step) 계산.
 *
 * <p>Kahn's BFS — 시작 노드(in-degree 0)는 step 0, 모든 선행이 처리된 후 step = max(선행 step) + 1.</p>
 *
 * <p>Pipeline 모드 게이트가 "단계 S+1, S+2의 노드"를 알아내기 위해 사용. 사이클이 있는 노드는
 * 결과 맵에서 제외(unreachable). 호출자는 게이트 검사 시 step이 없는 노드를 안전 통과 처리.</p>
 */
public final class LineDagTopo {

    private LineDagTopo() {}

    /**
     * 모든 도달 가능한 노드의 위상 단계 계산.
     *
     * @param nodes 정의 소속 활성 역
     * @param edges 정의 소속 활성 트랙 (선행→후행)
     * @return ``nodeId → step`` 맵. 사이클·unreachable 노드는 누락.
     */
    public static Map<String, Integer> computeStepLayers(List<LineStation> nodes, List<LineTrack> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (LineStation n : nodes) {
            inDegree.put(n.id(), 0);
            adj.put(n.id(), new java.util.ArrayList<>());
        }
        for (LineTrack e : edges) {
            // 안전 — 누락된 노드 무시
            if (!inDegree.containsKey(e.fromNodeId()) || !inDegree.containsKey(e.toNodeId())) continue;
            inDegree.merge(e.toNodeId(), 1, Integer::sum);
            adj.get(e.fromNodeId()).add(e.toNodeId());
        }

        // tentative — 모든 선행이 visit한 시점의 max(선행 step)+1 누적
        // finalStep — 사이클 외부에서 실제로 dequeue된 노드만 (in-degree 0 도달)
        Map<String, Integer> tentative = new HashMap<>();
        Map<String, Integer> finalStep = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> en : inDegree.entrySet()) {
            if (en.getValue() == 0) {
                queue.add(en.getKey());
                finalStep.put(en.getKey(), 0);
            }
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int curStep = finalStep.get(cur);
            for (String nxt : adj.getOrDefault(cur, List.of())) {
                int next = inDegree.merge(nxt, -1, Integer::sum);
                // 단계는 모든 선행의 max(선행 step) + 1 — 점진적 갱신
                tentative.merge(nxt, curStep + 1, Math::max);
                if (next == 0) {
                    finalStep.put(nxt, tentative.get(nxt));
                    queue.add(nxt);
                }
            }
        }
        return finalStep;
    }

    /** 주어진 step에 위치한 모든 노드 ID. */
    public static Set<String> nodesAtStep(Map<String, Integer> stepMap, int step) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Integer> en : stepMap.entrySet()) {
            if (en.getValue() == step) out.add(en.getKey());
        }
        return out;
    }

    /** 가장 큰 step (DAG 깊이 - 1). 비어 있으면 -1. */
    public static int maxStep(Map<String, Integer> stepMap) {
        int max = -1;
        for (int v : stepMap.values()) if (v > max) max = v;
        return max;
    }
}
