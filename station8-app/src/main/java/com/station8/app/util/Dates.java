package com.station8.app.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 사용자 가시 컬럼용 LocalDateTime 포맷 (#235).
 *
 * <p>{@link LocalDateTime#toString()}의 ISO-8601 출력은 {@code 2026-05-14T07:26:12}처럼
 * {@code T} 구분자를 포함해 가독성이 떨어진다. 모든 mustache 모델에서 본 포맷터로
 * {@code 2026-05-14 07:26:12} 형태로 통일.</p>
 *
 * <p>null-safe — null 입력은 null 반환 (mustache는 null/empty 값을 빈 문자열로 렌더).</p>
 */
public final class Dates {

    public static final DateTimeFormatter DEFAULT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Dates() {}

    public static String format(LocalDateTime dt) {
        return dt == null ? null : dt.format(DEFAULT);
    }
}
