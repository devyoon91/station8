package com.station8.app.controller.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard 페이지({@code GET /line/dashboard})의 모든 검색/페이징/정렬 파라미터를 묶은 요청 DTO.
 *
 * <p>컨트롤러가 {@code @RequestParam}으로 개별 파라미터를 받은 뒤 본 record로 묶어
 * {@link DashboardModelBuilder}에 전달한다 — 메서드 시그니처를 짧게 유지 + 동일한 데이터를
 * 인자 묶음 단위로 운반.</p>
 *
 * @param workflowName 정의 이름 부분일치
 * @param statusSt     인스턴스 상태 다중 선택 (RUNNING/COMPLETED/FAILED/TERMINATED)
 * @param instanceId   인스턴스 ID 부분일치
 * @param startDtFrom  시작일 from (포함)
 * @param startDtTo    시작일 to (포함, 23:59:59까지 자동 확장)
 * @param sortBy       정렬 컬럼 (REG_DT/START_DT/END_DT)
 * @param sortDir      정렬 방향 (ASC/DESC)
 * @param page         0-based 페이지 번호
 * @param size         페이지 크기
 * @param auto         auto-refresh 플래그 ({@code 1} 또는 {@code true}이면 활성)
 */
public record DashboardRequest(
        String workflowName,
        List<String> statusSt,
        String instanceId,
        LocalDate startDtFrom,
        LocalDate startDtTo,
        String sortBy,
        String sortDir,
        Integer page,
        Integer size,
        String auto
) {
}
