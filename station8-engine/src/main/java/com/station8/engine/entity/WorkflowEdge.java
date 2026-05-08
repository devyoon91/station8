package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_WF_EDGE 엔티티 — 노드 간 의존성 (FROM 완료 시 TO 활성화).
 */
public record WorkflowEdge(
    String id,
    String definitionId,
    String fromNodeId,
    String toNodeId,
    String conditionExpr,
    String useFl,
    String viewFl,
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId
) {
}
