package com.station8.app.definition;

import com.station8.engine.entity.LineProject;

import java.time.LocalDateTime;

/**
 * #168 — Project 조회 응답 DTO.
 *
 * @param id          PK
 * @param projectNm   이름
 * @param description 설명
 * @param regDt       생성 시각
 * @param regId       생성자
 */
public record LineProjectResponse(
        String id,
        String projectNm,
        String description,
        LocalDateTime regDt,
        String regId
) {
    /** 엔티티 → 응답 DTO 변환. */
    public static LineProjectResponse from(LineProject project) {
        return new LineProjectResponse(
                project.id(),
                project.projectNm(),
                project.description(),
                project.regDt(),
                project.regId()
        );
    }
}
