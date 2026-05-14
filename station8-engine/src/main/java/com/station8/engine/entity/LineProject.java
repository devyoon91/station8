package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * #168 — {@code U_LINE_PROJECT} 엔티티. 라인 정의의 1차 컨테이너.
 *
 * <p>Azkaban의 Project 패턴을 벤치마킹. 모든 {@link LineDefinition}은 정확히 1개의 Project에
 * 소속되며, {@code 'default'} project가 시드되어 projectId 미지정 정의의 fallback이다.</p>
 *
 * <p>Phase 1 — 1계층(Project)만 도입. Workspace 계층은 follow-up issue.</p>
 *
 * @param id          PK (UUID)
 * @param projectNm   고유 이름 (50자 권장, DB UNIQUE)
 * @param description 설명 (선택)
 * @param useFl       소프트 비활성화 flag
 * @param viewFl      가시성 flag
 * @param delFl       소프트 삭제 flag
 * @param regDt       생성 시각
 * @param regId       생성자 ID/시스템
 * @param editDt      마지막 수정 시각
 * @param editId      마지막 수정자
 */
public record LineProject(
        String id,
        String projectNm,
        String description,
        String useFl,
        String viewFl,
        String delFl,
        LocalDateTime regDt,
        String regId,
        LocalDateTime editDt,
        String editId
) {

    /** {@link LineProjectSeeder}가 시드하는 default project의 고정 ID — 운영/테스트가 직접 참조 가능. */
    public static final String DEFAULT_PROJECT_ID = "00000000-0000-0000-0000-000000000001";

    /** Default project의 표시 이름. */
    public static final String DEFAULT_PROJECT_NM = "default";
}
