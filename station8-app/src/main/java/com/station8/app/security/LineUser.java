package com.station8.app.security;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * U_LINE_USER 엔티티 — 자체 사용자 계정 (#121).
 *
 * <p>{@code passwordHash}는 BCrypt 해시 (raw 비밀번호는 절대 저장 안 함).
 * {@code roles}는 join 결과 — 단건 조회 시 채워짐, 목록 조회 시 별도 fetch.</p>
 */
public record LineUser(
        String id,
        String username,
        String passwordHash,
        String displayNm,
        String enabledFl,
        Set<String> roles,
        String delFl,
        LocalDateTime regDt,
        String regId,
        LocalDateTime editDt,
        String editId
) {
}
