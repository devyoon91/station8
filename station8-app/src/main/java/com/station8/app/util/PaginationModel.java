package com.station8.app.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mustache 페이지 네비용 view 모델 빌더 (#97).
 *
 * 컨트롤러가 ``build(...)``로 만들어 model에 ``"pagination"``로 넣으면,
 * ``_pagination.mustache`` 부분 템플릿이 그대로 렌더한다. 모든 URL은
 * 호출자측의 ``baseUrl`` + 보존할 ``preserveQuery`` 파라미터로 미리 빌드되어
 * 템플릿은 평면 키만 읽으면 된다.
 *
 * 페이지 사이즈는 {@link #ALLOWED_SIZES} 중 하나로 강제한다 (기본 20).
 */
public final class PaginationModel {

    public static final int DEFAULT_SIZE = 20;
    public static final List<Integer> ALLOWED_SIZES = List.of(20, 50, 100);

    private PaginationModel() {}

    /**
     * 사용자 입력 ``size``를 허용 목록 안 값으로 정규화. null/범위 밖이면 기본 20.
     */
    public static int normalizeSize(Integer size) {
        if (size == null) return DEFAULT_SIZE;
        return ALLOWED_SIZES.contains(size) ? size : DEFAULT_SIZE;
    }

    /**
     * 0-based ``page``를 ``[0, totalPages-1]`` 범위로 클램프. totalPages=0이면 0.
     */
    public static int normalizePage(Integer page, int totalPages) {
        int p = (page == null || page < 0) ? 0 : page;
        if (totalPages <= 0) return 0;
        return Math.min(p, totalPages - 1);
    }

    /**
     * @param baseUrl       페이지 라우트 경로 (예: ``/line/dashboard``)
     * @param page          현재 0-based 페이지 (정규화된 값)
     * @param size          현재 페이지 사이즈 (정규화된 값)
     * @param totalCount    필터 적용 후 총 행 수
     * @param preserveQuery 페이지/사이즈 변경 시 함께 유지할 검색 파라미터 (값이 null/blank면 자동 제외)
     */
    public static Map<String, Object> build(String baseUrl, int page, int size,
                                            long totalCount, Map<String, String> preserveQuery) {
        int totalPages = (totalCount <= 0) ? 0 : (int) ((totalCount + size - 1) / size);
        int curr = normalizePage(page, totalPages);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currentPage", curr);
        m.put("displayPage", curr + 1);          // 1-based 표시용
        m.put("totalPages", totalPages);
        m.put("size", size);
        m.put("totalCount", totalCount);
        m.put("hasResults", totalCount > 0);
        m.put("hasMultiplePages", totalPages > 1);
        m.put("hasPrev", curr > 0);
        m.put("hasNext", curr + 1 < totalPages);
        m.put("prevUrl", buildUrl(baseUrl, curr - 1, size, preserveQuery));
        m.put("nextUrl", buildUrl(baseUrl, curr + 1, size, preserveQuery));
        m.put("firstUrl", buildUrl(baseUrl, 0, size, preserveQuery));
        m.put("lastUrl", buildUrl(baseUrl, Math.max(0, totalPages - 1), size, preserveQuery));

        // 페이지 사이즈 셀렉터 — 사이즈 바꿀 때 page=0으로 리셋
        List<Map<String, Object>> sizeOptions = new ArrayList<>();
        for (Integer s : ALLOWED_SIZES) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("size", s);
            opt.put("isCurrent", s == size);
            opt.put("url", buildUrl(baseUrl, 0, s, preserveQuery));
            sizeOptions.add(opt);
        }
        m.put("sizeOptions", sizeOptions);
        return m;
    }

    private static String buildUrl(String baseUrl, int page, int size, Map<String, String> preserve) {
        StringBuilder sb = new StringBuilder(baseUrl).append("?page=").append(Math.max(0, page))
                .append("&size=").append(size);
        if (preserve != null) {
            for (Map.Entry<String, String> e : preserve.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isBlank()) continue;
                sb.append('&').append(e.getKey()).append('=').append(urlEncode(v));
            }
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
