package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_TRACK 엔티티 — 역 간 의존성 (FROM 완료 시 TO 활성화).
 */
public record LineTrack(
    String id,
    String definitionId,
    String fromNodeId,
    String toNodeId,
    String conditionExpr,
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId
) {
}
