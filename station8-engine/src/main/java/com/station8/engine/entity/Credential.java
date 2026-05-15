package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_CREDENTIAL — M17 (#270) credential vault entry.
 *
 * <p>{@code valueEnc}는 Base64 인코딩된 AES-GCM ciphertext (IV prepended).
 * 평문은 본 entity에 절대 들어가지 않으며, decrypt는
 * {@link com.station8.engine.crypto.CredentialCrypto}를 통해서만 수행한다.</p>
 *
 * <p>{@code schemaJson}은 타입별 옵셔널 메타 — 예: {@code http_basic}의 경우
 * {@code {"username": "..."}}로 평문 username을 보관 (비밀번호만 암호화).</p>
 */
public record Credential(
    String id,
    String name,
    String type,
    String valueEnc,
    String schemaJson,
    String delFl,
    LocalDateTime regDt,
    String regId,
    LocalDateTime editDt,
    String editId
) {
}
