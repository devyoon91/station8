package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_DEFINITION 엔티티 — 사용자가 정의한 DAG의 본체.
 */
public record LineDefinition(
    String id,
    String definitionNm,
    String description,
    int versionNo,
    String activeFl,
    String useFl,
    String viewFl,
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId
) {
}
