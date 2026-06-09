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
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId,
    /**
     * M22 fan-out 모드 — 이 역이 선행 배열 출력을 어떻게 다루는지.
     * <ul>
     *   <li>{@code NONE} (기본) — 선행 출력을 통째로 받음. 기존 동작.</li>
     *   <li>{@code FAN_OUT} — 선행 출력이 배열이면 원소마다 한 번씩 실행 (item-scoped).</li>
     *   <li>{@code COLLECT} — 선행 fan-out 레인의 모든 원소 출력을 배열로 모아 1회 실행.</li>
     * </ul>
     * null이면 {@code NONE}으로 취급.
     */
    String streamMode
) {
    public static final String STREAM_NONE = "NONE";
    public static final String STREAM_FAN_OUT = "FAN_OUT";
    public static final String STREAM_COLLECT = "COLLECT";

    /** null-safe 조회 — null/빈값이면 NONE. */
    public String streamModeOrDefault() {
        return (streamMode == null || streamMode.isBlank()) ? STREAM_NONE : streamMode;
    }
}
