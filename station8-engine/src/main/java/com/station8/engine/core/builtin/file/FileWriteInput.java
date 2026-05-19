package com.station8.engine.core.builtin.file;

import java.util.Locale;

/**
 * {@link FileWriteActivity} 입력.
 *
 * <p>content 타입 해석 = format에 따라:</p>
 * <ul>
 *   <li>{@code text} — content는 String. bytes로 encode</li>
 *   <li>{@code json} — content는 Object/Map/List/String. Jackson serialize</li>
 *   <li>{@code binary} — content는 Base64 String. decode 후 byte</li>
 * </ul>
 *
 * @param uri          `file:///path` 또는 절대 path. 필수
 * @param content      파일에 쓸 내용. format에 맞는 타입이어야
 * @param encoding     text 모드의 charset. null이면 UTF-8
 * @param format       `text` (default) / `json` / `binary`
 * @param credentialId vault 등록 이름. local은 무시, SFTP/S3는 인증에 사용. null OK
 */
public record FileWriteInput(
        String uri,
        Object content,
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
