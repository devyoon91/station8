package com.station8.engine.repository;

import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineEdge;
import com.station8.engine.entity.LineStation;

import java.util.List;

/**
 * DAG 정의(U_WF_DEFINITION/NODE/EDGE) 조회/저장 리포지토리.
 * M1-2 인터프리터에서 역 의존성 그래프를 탐색할 때 사용한다.
 */
public interface LineDefinitionRepository {

    LineDefinition findDefinitionById(String definitionId);

    /**
     * 살아있는(소프트 삭제 아님) 정의의 활성 버전만 반환.
     * 같은 ``definitionNm``이 여러 버전을 가지는 경우 ``ACTIVE_FL = 'Y'`` 한 행만 노출한다.
     * 정렬은 ``DEFINITION_NM ASC``.
     */
    List<LineDefinition> findAllActiveDefinitions();

    List<LineStation> findNodesByDefinition(String definitionId);

    List<LineEdge> findEdgesByDefinition(String definitionId);

    /** 후행 역 → 선행 역 조회 (fan-in 검사에 사용). */
    List<LineEdge> findIncomingEdges(String toNodeId);

    /** 선행 역 → 후행 역 조회 (fan-out 활성화에 사용). */
    List<LineEdge> findOutgoingEdges(String fromNodeId);

    /** 시작 역(들): incoming edge가 0개인 역. */
    List<LineStation> findStartNodes(String definitionId);

    /** 정의 INSERT. */
    void insertDefinition(LineDefinition definition);

    /** 정의 메타(DESC/ACTIVE_FL) 업데이트. */
    void updateDefinitionMeta(String definitionId, String description, String activeFl);

    /** 정의 소프트 삭제 (DEL_FL='Y'). */
    void softDeleteDefinition(String definitionId);

    /** 역 INSERT. */
    void insertNode(LineStation node);

    /** 정의 소속 역 일괄 소프트 삭제. */
    void softDeleteNodesByDefinition(String definitionId);

    /** 엣지 INSERT. */
    void insertEdge(LineEdge edge);

    /** 정의 소속 엣지 일괄 소프트 삭제. */
    void softDeleteEdgesByDefinition(String definitionId);

    /** 같은 이름의 활성 정의 중 최대 VERSION_NO. 없으면 0. */
    int findMaxVersionByName(String definitionNm);
}
