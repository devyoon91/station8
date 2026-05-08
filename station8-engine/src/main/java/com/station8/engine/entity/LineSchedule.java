package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_WF_SCHEDULE 엔티티 — DAG 정의에 cron을 매핑한 정기 실행 스케줄.
 *
 * <ul>
 *   <li>``cronExpr`` : Spring CronExpression 호환 표현식 (5/6 필드)</li>
 *   <li>``nextRunDt`` : 다음 실행 예정 시각 (폴러가 이 컬럼으로 만료 여부 판단)</li>
 *   <li>``pausedFl`` : 'Y'면 폴러가 무시</li>
 *   <li>``inputData`` : 정기 실행 시 인스턴스에 주입할 입력 JSON (nullable)</li>
 * </ul>
 */
public record LineSchedule(
    String id,
    String definitionId,
    String cronExpr,
    LocalDateTime nextRunDt,
    LocalDateTime lastRunDt,
    String pausedFl,
    String inputData,
    String useFl,
    String viewFl,
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId
) {
}
