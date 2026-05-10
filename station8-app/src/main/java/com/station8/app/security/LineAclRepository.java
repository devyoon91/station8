package com.station8.app.security;

import java.util.List;

/**
 * #140 — U_LINE_DEFINITION_ACL 리포지토리 인터페이스.
 */
public interface LineAclRepository {

    /** 권한 부여 — 이미 있으면 idempotent (UNIQUE 제약 무시). */
    void grant(String definitionId, String userId, String permission, String regId);

    /** 권한 회수 — 없으면 idempotent. */
    void revoke(String definitionId, String userId, String permission);

    /** 정의의 모든 ACL entry 조회 — 권한 관리 페이지 + ACL 평가용. */
    List<LineAclEntry> findByDefinition(String definitionId);

    /** 특정 사용자의 특정 정의에 대한 권한 목록. */
    List<String> findPermissionsForUser(String definitionId, String userId);

    /** 정의의 ADMIN 권한 보유자 수 — 마지막 ADMIN 강등 방지용. */
    int countAdminsForDefinition(String definitionId);

    /** 정의의 전체 ACL entry 수 — 0이면 legacy/open 상태로 간주. */
    int countEntriesForDefinition(String definitionId);
}
