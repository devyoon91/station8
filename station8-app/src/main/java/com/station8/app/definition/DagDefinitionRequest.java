package com.station8.app.definition;

import java.util.List;
import java.util.Map;

/**
 * DAG 정의 등록 요청 DTO.
 *
 * <pre>{@code
 * {
 *   "definitionNm": "OrderFlow",
 *   "description": "주문 처리 파이프라인",
 *   "nodes": [
 *     {"nodeId": "n-validate", "nodeNm": "Validate", "activityNm": "VALIDATE_ORDER", "posX": 100, "posY": 100,
 *      "datasourceBindings": {"orders": "ops-mysql"}},
 *     {"nodeId": "n-charge",   "nodeNm": "Charge",   "activityNm": "CHARGE_PAYMENT", "posX": 300, "posY": 100}
 *   ],
 *   "edges": [
 *     {"edgeId": "e1", "fromNodeId": "n-validate", "toNodeId": "n-charge"}
 *   ]
 * }
 * }</pre>
 *
 * 모든 식별자(nodeId/edgeId)는 클라이언트가 발급한다 (UUID 권장). 서버는 DB에 그대로 저장한다.
 *
 * <p>{@code datasourceBindings}(#113): 역(station)에서 사용할 DataSource 매핑 (role → registry 이름).
 * 액티비티가 {@code @BoundDataSource("role")}로 선언한 파라미터에 매핑된 풀이 주입된다. 미선언/누락 시
 * {@code primary} fallback.</p>
 */
public record DagDefinitionRequest(
        String definitionNm,
        String description,
        /** #138 — SLA 시간 임계치 (초). null이면 SLA 비활성. 인스턴스 RUN_OPTIONS로 override 가능. */
        Long slaSeconds,
        /** #138 — SLA 위반 시 액션 (`ALERT_ONLY` / `AUTO_TERMINATE`). null이면 ALERT_ONLY 기본. */
        String slaAction,
        List<NodeDef> nodes,
        List<EdgeDef> edges
) {
    /** 후방 호환 — SLA 없이 기존 4-arg 생성. */
    public DagDefinitionRequest(String definitionNm, String description,
                                List<NodeDef> nodes, List<EdgeDef> edges) {
        this(definitionNm, description, null, null, nodes, edges);
    }
    public record NodeDef(
            String nodeId,
            String nodeNm,
            String activityNm,
            String inputParams,
            Integer posX,
            Integer posY,
            /** role → DataSource registry name (#113). null 또는 빈 맵이면 모든 binding은 primary fallback. */
            Map<String, String> datasourceBindings
    ) {
    }

    public record EdgeDef(
            String edgeId,
            String fromNodeId,
            String toNodeId,
            String conditionExpr
    ) {
    }
}
