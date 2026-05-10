package com.station8.app.controller.dlq;

import java.time.LocalDate;
import java.util.List;

/**
 * DLQ 페이지({@code GET /line/dlq})의 모든 검색/페이징/정렬 파라미터를 묶은 요청 DTO.
 *
 * @param workflowName  정의 이름 부분일치
 * @param activityName  활동 이름 부분일치
 * @param errorMessage  에러 메시지 부분일치
 * @param dlqStatusSt   DLQ 상태 다중 선택 (NEW/REQUEUED/DISCARDED)
 * @param failedAtFrom  실패 시각 from (포함)
 * @param failedAtTo    실패 시각 to (포함, 23:59:59까지 자동 확장)
 * @param sortBy        정렬 컬럼 (REG_DT/FAILED_AT_DT/ACTIVITY_NAME)
 * @param sortDir       정렬 방향 (ASC/DESC)
 * @param page          0-based 페이지 번호
 * @param size          페이지 크기
 */
public record DlqRequest(
        String workflowName,
        String activityName,
        String errorMessage,
        List<String> dlqStatusSt,
        LocalDate failedAtFrom,
        LocalDate failedAtTo,
        String sortBy,
        String sortDir,
        Integer page,
        Integer size
) {
}
