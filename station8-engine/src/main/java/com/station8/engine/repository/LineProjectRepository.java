package com.station8.engine.repository;

import com.station8.engine.entity.LineProject;

import java.util.List;

/**
 * #168 — {@code U_LINE_PROJECT} CRUD repository.
 *
 * <p>Project는 1차 컨테이너 — 본 PR(Phase 1) 범위에서는 CRUD + read 조회만 제공.
 * 권한(#140 ACL 확장) + UI 매핑은 follow-up PR.</p>
 */
public interface LineProjectRepository {

    /** ID로 단건 조회. 소프트 삭제된 행은 제외. {@code null} = 없음. */
    LineProject findById(String projectId);

    /** 이름으로 단건 조회. 소프트 삭제된 행은 제외. {@code null} = 없음. */
    LineProject findByName(String projectNm);

    /** 살아있는 모든 프로젝트 (정렬: {@code PROJECT_NM ASC}). */
    List<LineProject> findAll();

    /** 신규 등록. ID/이름 중복은 호출 측 검증 후 호출 (UNIQUE 위반 시 예외 전파). */
    void insert(LineProject project);

    /** 이름/설명 업데이트. */
    void updateMeta(String projectId, String projectNm, String description, String editId);

    /** 소프트 삭제 ({@code DEL_FL='Y'}). 이미 삭제된 경우 멱등. */
    void softDelete(String projectId);

    /** 같은 이름의 프로젝트 존재 여부 (소프트 삭제된 행 제외). */
    boolean existsByName(String projectNm);
}
