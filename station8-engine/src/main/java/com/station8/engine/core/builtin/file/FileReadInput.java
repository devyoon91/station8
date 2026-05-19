package com.station8.engine.core.builtin.file;

import java.util.Locale;

/**
 * {@link FileReadActivity} 입력 — JSON으로 받아 record로 deserialize.
 *
 * <p>표현식 평가는 활동 호출 전에 이미 끝남 — 본 record는 final value를 들고 있다.</p>
 *
 * @param uri          `file:///path` 또는 절대 path. 필수
 * @param encoding     텍스트 디코딩 charset. null이면 UTF-8. binary 모드에선 무시
 * @param format       `text` (default) / `json` / `binary`. csv는 별도 sub-issue
 * @param credentialId vault 등록 이름. local은 무시, SFTP/S3는 인증에 사용. null OK
 */
public record FileReadInput(
        String uri,
        String encoding,
        String format,
        String credentialId
) {
    public String effectiveEncoding() {
        return (encoding == null || encoding.isBlank()) ? "UTF-8" : encoding;
    }

    public String effectiveFormat() {
        return (format == null || format.isBlank()) ? "text" : format.toLowerCase(Locale.ROOT);
    }
}
