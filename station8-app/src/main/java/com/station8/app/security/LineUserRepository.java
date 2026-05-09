package com.station8.app.security;

import java.util.List;

/**
 * 자체 사용자 (#121) 영속화 리포지토리.
 *
 * <p>{@link LineUser#roles}는 별도 테이블({@code U_LINE_USER_ROLE}) — repo 메서드는 join을 통해
 * 사용자 + 역할을 함께 반환한다.</p>
 */
public interface LineUserRepository {

    /** 사용자 + 역할 join 단건. 없으면 null. */
    LineUser findByUsername(String username);

    /** ID로 단건. 없으면 null. */
    LineUser findById(String id);

    /** 활성(DEL_FL='N') 사용자 + 각 사용자 역할. */
    List<LineUser> findAll();

    long count();

    /** 사용자 + 역할 INSERT. role set이 비어있으면 USER 역할만. */
    void insert(LineUser user);

    /** 비밀번호 해시 업데이트. */
    void updatePasswordHash(String userId, String newHash, String editId);

    /** display name 변경. */
    void updateDisplayName(String userId, String displayNm, String editId);

    /** ENABLED_FL 토글. */
    void setEnabled(String userId, boolean enabled, String editId);

    /** 소프트 삭제 — 사용자 + 역할 모두. */
    void softDelete(String userId, String editId);
}
