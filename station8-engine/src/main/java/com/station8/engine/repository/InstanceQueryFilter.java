package com.station8.engine.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * U_LINE_INSTANCE 페이지 조회용 필터 (#137).
 *
 * <p>모든 필드는 선택. null/blank이면 해당 조건은 무시.</p>
 *
 * <ul>
 *   <li>{@code workflowName}: ``WORKFLOW_NAME LIKE %name%`` — 부분일치</li>
 *   <li>{@code statusList}: ``STATUS_ST IN (...)`` — 빈 리스트면 무시 (다중 선택 — D2)</li>
 *   <li>{@code instanceId}: ``ID LIKE %id%`` — 부분일치</li>
 *   <li>{@code startDtFrom} / {@code startDtTo}: ``START_DT BETWEEN`` — inclusive 양 끝 (D5)</li>
 *   <li>{@code sortBy}: ``REG_DT`` (default) / ``START_DT`` / ``END_DT`` — 화이트리스트 외 값은 default</li>
 *   <li>{@code sortDir}: ``DESC`` (default) / ``ASC`` — 그 외는 default</li>
 *   <li>{@code workflowNameAllowList} (#159): ``WORKFLOW_NAME IN (...)`` — null=무시(ADMIN/필터 없음),
 *       빈 set=조회 결과 0행, 그 외=ACL READ 가시 정의의 이름 목록</li>
 * </ul>
 *
 * <p>compact constructor에서 sortBy/sortDir 화이트리스트 검증 + 정규화 → SQL 인젝션 방지.</p>
 */
public record InstanceQueryFilter(
        String workflowName,
        List<String> statusList,
        String instanceId,
        LocalDateTime startDtFrom,
        LocalDateTime startDtTo,
        String sortBy,
        String sortDir,
        Set<String> workflowNameAllowList
) {
    /** 정렬 가능 컬럼 — 시간 컬럼만. status / workflowName은 group/filter가 더 적합 (D9). */
    public static final Set<String> ALLOWED_SORT = Set.of("REG_DT", "START_DT", "END_DT");
    public static final Set<String> ALLOWED_DIR = Set.of("ASC", "DESC");

    public InstanceQueryFilter {
        if (sortBy == null || sortBy.isBlank() || !ALLOWED_SORT.contains(sortBy.toUpperCase())) {
            sortBy = "REG_DT";
        } else {
            sortBy = sortBy.toUpperCase();
        }
        if (sortDir == null || sortDir.isBlank() || !ALLOWED_DIR.contains(sortDir.toUpperCase())) {
            sortDir = "DESC";
        } else {
            sortDir = sortDir.toUpperCase();
        }
    }

    /** #159 이전 7-arg 시그니처 — workflowNameAllowList=null로 위임. */
    public InstanceQueryFilter(String workflowName, List<String> statusList, String instanceId,
                               LocalDateTime startDtFrom, LocalDateTime startDtTo,
                               String sortBy, String sortDir) {
        this(workflowName, statusList, instanceId, startDtFrom, startDtTo, sortBy, sortDir, null);
    }

    /** 후방 호환 — 기존 단일 status / 검색 인자만 받던 호출에서 사용. */
    public static InstanceQueryFilter ofLegacy(String workflowName, String statusSt, String instanceId) {
        List<String> statuses = (statusSt == null || statusSt.isBlank()) ? null : List.of(statusSt);
        return new InstanceQueryFilter(workflowName, statuses, instanceId, null, null, null, null);
    }

    /** 빈 필터 (전체 조회). */
    public static InstanceQueryFilter empty() {
        return new InstanceQueryFilter(null, null, null, null, null, null, null);
    }

    /** #159 — 기존 필터에 workflow_name IN 제약을 더해 사본 반환. null/ADMIN 케이스는 그대로. */
    public InstanceQueryFilter withWorkflowNameAllowList(Set<String> allowList) {
        return new InstanceQueryFilter(workflowName, statusList, instanceId,
                startDtFrom, startDtTo, sortBy, sortDir, allowList);
    }
}
