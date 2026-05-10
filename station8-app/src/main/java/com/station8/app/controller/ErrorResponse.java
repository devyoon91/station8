package com.station8.app.controller;

/**
 * #174 — REST 응답용 표준 에러 페이로드.
 *
 * <p>모든 controller가 동일한 형식을 사용해 클라이언트가 단일 매핑으로 파싱 가능.</p>
 *
 * <ul>
 *   <li>{@code errorCode} — 도메인 에러 코드 (예: {@code DAG_INVALID}). 일반 입력 오류는 null.</li>
 *   <li>{@code message} — 사용자/운영자에게 노출할 메시지 (한국어 권장).</li>
 *   <li>{@code details} — 추가 메타데이터 (선택). Bean Validation field error 등에 사용.</li>
 * </ul>
 */
public record ErrorResponse(String errorCode, String message, Object details) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(null, message, null);
    }

    public static ErrorResponse of(String errorCode, String message) {
        return new ErrorResponse(errorCode, message, null);
    }

    public static ErrorResponse of(String errorCode, String message, Object details) {
        return new ErrorResponse(errorCode, message, details);
    }
}
