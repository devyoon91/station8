package com.station8.engine.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * H_LINE_DLQ 페이지 조회용 필터 (#137).
 *
 * <p>모든 필드는 선택. null/blank이면 해당 조건은 무시.</p>
 *
 * <ul>
 *   <li>{@code workflowName}: ``WORKFLOW_NAME LIKE`` — 부분일치</li>
 *   <li>{@code activityName}: ``ACTIVITY_NAME LIKE`` — 부분일치 (#137 신규)</li>
 *   <li>{@code errorMessage}: ``ERROR_MESSAGE LIKE`` — 부분일치 (#137 신규)</li>
 *   <li>{@code dlqStatusList}: ``DLQ_STATUS_ST IN (...)`` — 다중 선택</li>
 *   <li>{@code failedAtFrom} / {@code failedAtTo}: ``FAILED_AT_DT BETWEEN`` (#137 신규, inclusive)</li>
 *   <li>{@code sortBy}: ``REG_DT`` (default) / ``FAILED_AT_DT`` / ``ACTIVITY_NAME``</li>
 *   <li>{@code sortDir}: ``DESC`` (default) / ``ASC``</li>
 * </ul>
 */
public record DlqQueryFilter(
        String workflowName,
        String activityName,
        String errorMessage,
        List<String> dlqStatusList,
        LocalDateTime failedAtFrom,
        LocalDateTime failedAtTo,
        String sortBy,
        String sortDir,
        Set<String> workflowNameAllowList
) {
    public static final Set<String> ALLOWED_SORT = Set.of("REG_DT", "FAILED_AT_DT", "ACTIVITY_NAME");
    public static final Set<String> ALLOWED_DIR = Set.of("ASC", "DESC");

    public DlqQueryFilter {
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

    /** #159 이전 8-arg 시그니처 — workflowNameAllowList=null로 위임. */
    public DlqQueryFilter(String workflowName, String activityName, String errorMessage,
                          List<String> dlqStatusList, LocalDateTime failedAtFrom, LocalDateTime failedAtTo,
                          String sortBy, String sortDir) {
        this(workflowName, activityName, errorMessage, dlqStatusList,
                failedAtFrom, failedAtTo, sortBy, sortDir, null);
    }

    /** 빈 필터 (전체 조회). */
    public static DlqQueryFilter empty() {
        return new DlqQueryFilter(null, null, null, null, null, null, null, null);
    }

    /** #159 — workflow_name IN 제약 추가 사본. null=ADMIN/필터 없음, 빈 set=0행. */
    public DlqQueryFilter withWorkflowNameAllowList(Set<String> allowList) {
        return new DlqQueryFilter(workflowName, activityName, errorMessage, dlqStatusList,
                failedAtFrom, failedAtTo, sortBy, sortDir, allowList);
    }
}
