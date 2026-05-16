package com.station8.app.credential;

import com.station8.engine.entity.Credential;

import java.time.LocalDateTime;

/**
 * M17 (#271) — credential 조회 응답.
 *
 * <p>응답 직렬화에 평문이나 ciphertext는 절대 포함되지 않는다 ({@code valueEnc}/{@code value}
 * 필드 자체 없음). 응답으로는 메타만 노출 — 운영자가 등록 사실 + 식별을 위한 정보.</p>
 */
public record CredentialResponse(
        String id,
        String name,
        String type,
        String schemaJson,
        LocalDateTime regDt,
        LocalDateTime editDt
) {
    public static CredentialResponse from(Credential c) {
        return new CredentialResponse(
                c.id(), c.name(), c.type(), c.schemaJson(), c.regDt(), c.editDt());
    }
}
