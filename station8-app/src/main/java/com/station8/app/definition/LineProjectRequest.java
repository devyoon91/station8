package com.station8.app.definition;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * #168 — Project 생성/수정 요청 DTO.
 *
 * @param projectNm   프로젝트 이름. 필수, 1~100자. DB UNIQUE.
 * @param description 설명 (선택). 자유 텍스트.
 */
public record LineProjectRequest(
        @NotBlank(message = "projectNm은 필수입니다.")
        @Size(max = 100, message = "projectNm은 100자를 초과할 수 없습니다.")
        String projectNm,

        @Size(max = 500, message = "description은 500자를 초과할 수 없습니다.")
        String description
) {
}
