package com.bangrang.workflow.app.definition;

import java.util.List;

/**
 * DAG 정의 등록 요청 DTO.
 *
 * <pre>{@code
 * {
 *   "definitionNm": "OrderFlow",
 *   "description": "주문 처리 파이프라인",
 *   "nodes": [
 *     {"nodeId": "n-validate", "nodeNm": "Validate", "activityNm": "VALIDATE_ORDER", "posX": 100, "posY": 100},
 *     {"nodeId": "n-charge",   "nodeNm": "Charge",   "activityNm": "CHARGE_PAYMENT", "posX": 300, "posY": 100}
 *   ],
 *   "edges": [
 *     {"edgeId": "e1", "fromNodeId": "n-validate", "toNodeId": "n-charge"}
 *   ]
 * }
 * }</pre>
 *
 * 모든 식별자(nodeId/edgeId)는 클라이언트가 발급한다 (UUID 권장). 서버는 DB에 그대로 저장한다.
 */
public record DagDefinitionRequest(
        String definitionNm,
        String description,
        List<NodeDef> nodes,
        List<EdgeDef> edges
) {
    public record NodeDef(
            String nodeId,
            String nodeNm,
            String activityNm,
            String inputParams,
            Integer posX,
            Integer posY
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
