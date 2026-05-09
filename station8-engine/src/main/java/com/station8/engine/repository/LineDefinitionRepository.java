package com.station8.engine.repository;

import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineStation;

import java.util.List;

/**
 * DAG 정의(U_LINE_DEFINITION/NODE/EDGE) 조회/저장 리포지토리.
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

    /** 활성 정의 페이지 조회 — 같은 정렬 키(``DEFINITION_NM ASC``) (#97). */
    List<LineDefinition> findActiveDefinitionsPage(int offset, int limit);

    /** 활성 정의 총 행 수 (#97). */
    long countActiveDefinitions();

    /**
     * ``U_LINE_STATION.ID``로부터 소속 ``DEFINITION_ID``를 역조회한다.
     * 인스턴스 → 액티비티 실행 → ``nodeId``를 거쳐 라인 정의를 찾을 때 사용 (#87 M2).
     * 소프트 삭제된 역도 매칭한다 (인스턴스 실행 당시 정의로 거슬러 올라가야 하므로).
     *
     * @return ``DEFINITION_ID`` 또는 ``null``(노드 없음)
     */
    String findDefinitionIdByNodeId(String nodeId);

    /**
     * ``U_LINE_STATION.ID``로 단일 역 조회 — DataSource 바인딩(#113) 포함 모든 필드.
     * 소프트 삭제된 역도 반환 (실행 당시 정의로 거슬러 올라가는 경우).
     *
     * @return 해당 역 또는 ``null``
     */
    LineStation findStationById(String stationId);

    List<LineStation> findNodesByDefinition(String definitionId);

    List<LineTrack> findEdgesByDefinition(String definitionId);

    /** 후행 역 → 선행 역 조회 (fan-in 검사에 사용). */
    List<LineTrack> findIncomingEdges(String toNodeId);

    /** 선행 역 → 후행 역 조회 (fan-out 활성화에 사용). */
    List<LineTrack> findOutgoingEdges(String fromNodeId);

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
    void insertEdge(LineTrack edge);

    /** 정의 소속 엣지 일괄 소프트 삭제. */
    void softDeleteEdgesByDefinition(String definitionId);

    /** 같은 이름의 활성 정의 중 최대 VERSION_NO. 없으면 0. */
    int findMaxVersionByName(String definitionNm);
}
