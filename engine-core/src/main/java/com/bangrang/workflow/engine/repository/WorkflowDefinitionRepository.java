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

    /** 정의 INSERT. */
    void insertDefinition(WorkflowDefinition definition);

    /** 정의 메타(DESC/ACTIVE_FL) 업데이트. */
    void updateDefinitionMeta(String definitionId, String description, String activeFl);

    /** 정의 소프트 삭제 (DEL_FL='Y'). */
    void softDeleteDefinition(String definitionId);

    /** 노드 INSERT. */
    void insertNode(WorkflowNode node);

    /** 정의 소속 노드 일괄 소프트 삭제. */
    void softDeleteNodesByDefinition(String definitionId);

    /** 엣지 INSERT. */
    void insertEdge(WorkflowEdge edge);

    /** 정의 소속 엣지 일괄 소프트 삭제. */
    void softDeleteEdgesByDefinition(String definitionId);

    /** 같은 이름의 활성 정의 중 최대 VERSION_NO. 없으면 0. */
    int findMaxVersionByName(String definitionNm);
}
