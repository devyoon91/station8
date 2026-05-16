package com.station8.app.credential;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * M17 (#271) — credential 생성/수정 요청.
 *
 * <p>{@code value}는 평문으로 받아 즉시 암호화 후 저장. 응답({@link CredentialResponse})에는
 * 절대 포함되지 않음. PUT 시 {@code value}가 {@code null}이면 기존 ciphertext 유지 (rotate 안 함).</p>
 */
public record CredentialRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100)
        String name,

        @NotBlank(message = "type is required")
        @Size(max = 50)
        String type,

        /** POST: 필수. PUT: 선택 — null이면 기존 value 유지, 비-null이면 rotate. */
        String value,

        /** 옵셔널 — 타입별 메타 (e.g. http_basic의 username). */
        String schemaJson
) {
}
