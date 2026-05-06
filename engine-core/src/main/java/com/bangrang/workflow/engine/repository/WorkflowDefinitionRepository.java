package com.bangrang.workflow.engine.repository;

import com.bangrang.workflow.engine.entity.WorkflowDefinition;
import com.bangrang.workflow.engine.entity.WorkflowEdge;
import com.bangrang.workflow.engine.entity.WorkflowNode;

import java.util.List;

/**
 * DAG 정의(U_WF_DEFINITION/NODE/EDGE) 조회/저장 리포지토리.
 * M1-2 인터프리터에서 노드 의존성 그래프를 탐색할 때 사용한다.
 */
public interface WorkflowDefinitionRepository {

    WorkflowDefinition findDefinitionById(String definitionId);

    List<WorkflowNode> findNodesByDefinition(String definitionId);

    List<WorkflowEdge> findEdgesByDefinition(String definitionId);

    /** 후행 노드 → 선행 노드 조회 (fan-in 검사에 사용). */
    List<WorkflowEdge> findIncomingEdges(String toNodeId);

    /** 선행 노드 → 후행 노드 조회 (fan-out 활성화에 사용). */
    List<WorkflowEdge> findOutgoingEdges(String fromNodeId);

    /** 시작 노드(들): incoming edge가 0개인 노드. */
    List<WorkflowNode> findStartNodes(String definitionId);
}
