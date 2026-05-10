package com.station8.app.definition.list;

/**
 * 라인 정의 목록 페이지({@code GET /line/definitions})의 검색/페이징 파라미터 묶음 DTO.
 *
 * @param page       0-based 페이지 번호 (null = 0)
 * @param size       페이지 크기 (null = default 20)
 * @param nameFilter 정의 이름 부분일치 (null/blank = 미적용)
 * @param tagFilter  태그 정확일치 (null/blank = 미적용)
 */
public record DefinitionsListRequest(
        Integer page,
        Integer size,
        String nameFilter,
        String tagFilter
) {
}
