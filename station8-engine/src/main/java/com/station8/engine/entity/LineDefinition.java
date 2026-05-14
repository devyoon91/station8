package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_DEFINITION 엔티티 — 사용자가 정의한 DAG의 본체.
 *
 * @param projectId #168 — 소속 프로젝트 ID. Phase 1에서는 모두 {@link LineProject#DEFAULT_PROJECT_ID}로
 *                  backfill 또는 신규 정의의 default. 명시 지정은 Phase 2 (UI/API)에서 도입.
 */
public record LineDefinition(
    String id,
    String definitionNm,
    String description,
    int versionNo,
    String activeFl,
    Long slaSeconds,           // #138: NULL = SLA 비활성
    String slaAction,          // #138: ALERT_ONLY / AUTO_TERMINATE / null
    String concurrencyPolicy,  // #141: CONCURRENT(default) / SKIP_IF_RUNNING / null
    String projectId,          // #168: 소속 프로젝트 (default 또는 명시 지정)
    String useFl,
    String viewFl,
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId
) {
}
