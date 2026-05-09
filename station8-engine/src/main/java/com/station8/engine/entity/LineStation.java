package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_STATION 엔티티 — DAG 정의 내 역(=액티비티 호출 단위).
 *
 * <p>{@code datasourceBindings} (#113): 이 역에서 액티비티가 사용할 DataSource 매핑(JSON).
 * 형식: <code>{"role":"registry-name", ...}</code>. 액티비티가 {@code @BoundDataSource("role")}을
 * 선언한 파라미터에 매핑된 풀이 주입된다. null/빈 문자열이면 모든 binding은 {@code primary}로 fallback.</p>
 */
public record LineStation(
    String id,
    String definitionId,
    String nodeNm,
    String activityNm,
    String inputParams,
    /** JSON map<role, datasource name> — null/빈값이면 fallback to primary. */
    String datasourceBindings,
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
