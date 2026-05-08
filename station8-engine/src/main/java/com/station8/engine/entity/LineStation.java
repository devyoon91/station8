package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_STATION 엔티티 — DAG 정의 내 역(=액티비티 호출 단위).
 */
public record LineStation(
    String id,
    String definitionId,
    String nodeNm,
    String activityNm,
    String inputParams,
    Integer posXNo,
    Integer posYNo,
    String useFl,
    String viewFl,
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId
) {
}
