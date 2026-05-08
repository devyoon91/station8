package com.station8.engine.core;

import com.station8.engine.entity.WorkflowEdge;
import com.station8.engine.entity.WorkflowNode;
import com.station8.engine.exception.ErrorCodes;
import com.station8.engine.exception.WorkflowEngineException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DAG 정의 검증기. 다음 위반을 모두 수집하여 한 번에 보고한다.
 * <ul>
 *   <li>노드 0개 (DAG_NO_NODES)</li>
 *   <li>시작 노드(incoming 0) 없음 (DAG_NO_START_NODE)</li>
 *   <li>종료 노드(outgoing 0) 없음 (DAG_NO_TERMINAL_NODE)</li>
 *   <li>사이클 존재 (DAG_CYCLE_DETECTED) — Kahn 위상 정렬 기반 검출</li>
 *   <li>자기-참조 엣지 (DAG_SELF_LOOP)</li>
 *   <li>등록되지 않은 액티비티 참조 (DAG_UNKNOWN_ACTIVITY)</li>
 *   <li>정의 외부 노드를 참조하는 엣지 (DAG_DANGLING_EDGE)</li>
 * </ul>
 *
 * 호출 시점:
 *   - DAG 정의 저장 API(M1-4): 사용자에게 즉시 피드백
 *   - DagInterpreter.startInstance: 실행 직전 안전망
 */
@Component
public class DagValidator {

    /**
     * 모든 위반을 수집하여 검증한다. 위반이 있으면 ``DAG_INVALID``로 묶어 한 번에 예외 발생.
     *
     * @param nodes 정의의 모든 노드
     * @param edges 정의의 모든 엣지
     * @param registeredActivityNames {@code WorkflowRegistry}가 보유한 액티비티 이름 집합 (검증 생략 시 ``null``)
     */
    public void validate(List<WorkflowNode> nodes,
                         List<WorkflowEdge> edges,
                         Set<String> registeredActivityNames) {
        List<String> violations = new ArrayList<>();

        // 1) 노드 0개
        if (nodes.isEmpty()) {
            violations.add(ErrorCodes.DAG_NO_NODES + ": 정의에 노드가 없습니다");
            // 노드 0개면 이후 검사 무의미
            throwIfAny(violations);
            return;
        }

        Set<String> nodeIds = nodes.stream().map(WorkflowNode::id).collect(Collectors.toSet());

        // 2) 자기-참조 + dangling edge
        for (WorkflowEdge e : edges) {
            if (e.fromNodeId() != null && e.fromNodeId().equals(e.toNodeId())) {
                violations.add(ErrorCodes.DAG_SELF_LOOP + ": edgeId=" + e.id() + ", node=" + e.fromNodeId());
            }
            if (!nodeIds.contains(e.fromNodeId()) || !nodeIds.contains(e.toNodeId())) {
                violations.add(ErrorCodes.DAG_DANGLING_EDGE
                        + ": edgeId=" + e.id() + ", from=" + e.fromNodeId() + ", to=" + e.toNodeId());
            }
        }

        // 3) 미등록 액티비티 참조
        if (registeredActivityNames != null) {
            for (WorkflowNode n : nodes) {
                if (!registeredActivityNames.contains(n.activityNm())) {
                    violations.add(ErrorCodes.DAG_UNKNOWN_ACTIVITY
                            + ": nodeId=" + n.id() + ", activityNm=" + n.activityNm());
                }
            }
        }

        // 4) 시작/종료 노드 존재
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Integer> outgoing = new HashMap<>();
        for (String nid : nodeIds) {
            incoming.put(nid, 0);
            outgoing.put(nid, 0);
        }
        for (WorkflowEdge e : edges) {
            if (nodeIds.contains(e.fromNodeId()) && nodeIds.contains(e.toNodeId())
                    && !e.fromNodeId().equals(e.toNodeId())) {
                outgoing.merge(e.fromNodeId(), 1, Integer::sum);
                incoming.merge(e.toNodeId(), 1, Integer::sum);
            }
        }
        boolean hasStart = incoming.values().stream().anyMatch(c -> c == 0);
        boolean hasTerminal = outgoing.values().stream().anyMatch(c -> c == 0);
        if (!hasStart) {
            violations.add(ErrorCodes.DAG_NO_START_NODE + ": incoming edge가 0개인 노드가 없음");
        }
        if (!hasTerminal) {
            violations.add(ErrorCodes.DAG_NO_TERMINAL_NODE + ": outgoing edge가 0개인 노드가 없음");
        }

        // 5) 사이클 검출 (Kahn 위상 정렬)
        // 자기-참조 엣지는 위 카운트에서 제외했지만, 그 노드 자체는 사이클이므로 명시 보고
        for (WorkflowEdge e : edges) {
            if (e.fromNodeId() != null && e.fromNodeId().equals(e.toNodeId())) {
                violations.add(ErrorCodes.DAG_CYCLE_DETECTED
                        + ": self-loop를 사이클로 간주, nodeId=" + e.fromNodeId());
            }
        }
        Map<String, Integer> indegree = new HashMap<>(incoming);
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String nid : nodeIds) adjacency.put(nid, new ArrayList<>());
        for (WorkflowEdge e : edges) {
            if (nodeIds.contains(e.fromNodeId()) && nodeIds.contains(e.toNodeId())
                    && !e.fromNodeId().equals(e.toNodeId())) {
                adjacency.get(e.fromNodeId()).add(e.toNodeId());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> en : indegree.entrySet()) {
            if (en.getValue() == 0) queue.add(en.getKey());
        }
        int processed = 0;
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (!visited.add(n)) continue;
            processed++;
            for (String next : adjacency.get(n)) {
                int d = indegree.merge(next, -1, Integer::sum);
                if (d == 0) queue.add(next);
            }
        }
        if (processed < nodeIds.size()) {
            // 처리되지 않은 노드들이 사이클을 형성
            List<String> stuck = indegree.entrySet().stream()
                    .filter(en -> en.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            violations.add(ErrorCodes.DAG_CYCLE_DETECTED
                    + ": 사이클에 포함된 노드(잔여 indegree>0): " + stuck);
        }

        throwIfAny(violations);
    }

    private void throwIfAny(List<String> violations) {
        if (violations.isEmpty()) return;
        String message = "DAG 검증 실패 (" + violations.size() + "건): "
                + String.join(" | ", violations);
        throw new WorkflowEngineException(ErrorCodes.DAG_INVALID, message);
    }
}
