package com.station8.app.security;

import java.time.LocalDateTime;

/**
 * U_LINE_DEFINITION_ACL 엔티티 (#140) — 라인 정의별 사용자 권한 grant.
 *
 * <p>{@code permission} 값:</p>
 * <ul>
 *   <li>{@code READ} — 정의/인스턴스 조회 (1차 비범위 — 모든 USER 통과)</li>
 *   <li>{@code WRITE} — 정의 수정/삭제</li>
 *   <li>{@code EXECUTE} — 즉시 실행 + 인스턴스 제어 (resume/pause/unpause/terminate/activity retry)</li>
 *   <li>{@code SCHEDULE} — cron 등록/수정/삭제</li>
 *   <li>{@code ADMIN} — 위 전체 + 권한 부여/회수</li>
 * </ul>
 */
public record LineAclEntry(
        String id,
        String definitionId,
        String userId,
        String permission,
        String useFl,
        String viewFl,
        String delFl,
        LocalDateTime regDt,
        String regId,
        LocalDateTime editDt,
        String editId
) {
}
