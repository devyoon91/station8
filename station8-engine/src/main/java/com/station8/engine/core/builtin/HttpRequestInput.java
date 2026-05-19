package com.station8.engine.core.builtin;

import java.util.Map;

/**
 * {@link HttpRequestActivity}의 입력 파라미터 — JSON으로 받아 record로 deserialize.
 *
 * <p>모든 값은 expression engine이 이미 풀어낸 final value. 활동 메서드는 정적 dispatch만 한다.</p>
 *
 * @param method       HTTP 메서드 — GET/POST/PUT/DELETE/PATCH (대소문자 무시). 필수
 * @param url          절대 URL. 표현식 평가 후 final. SSRF 방어는 별도 sub-issue (#289)
 * @param headers      추가 헤더. credential 자동 주입 헤더와 충돌 시 본 값이 win
 * @param body         POST/PUT/PATCH 본문. String이면 그대로, Map이면 JSON serialize. GET/DELETE에선 무시
 * @param timeoutMs    소켓 타임아웃 (ms). null → 30000, max 300000 (5분)
 * @param credentialId M17 credential 이름. 타입에 따라 Authorization 등 자동 주입. null이면 자동 헤더 0
 */
public record HttpRequestInput(
        String method,
        String url,
        Map<String, String> headers,
        Object body,
        Long timeoutMs,
        String credentialId
) {
    /** 기본 timeout — 30초. 사용자가 timeoutMs를 안 주면 적용. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** 최대 timeout — 5분. 그 이상은 활동 단위로 부적절 (라인이 5분 안에 떠야). */
    public static final long MAX_TIMEOUT_MS = 300_000L;

    public long effectiveTimeoutMs() {
        long t = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
        if (t <= 0) return DEFAULT_TIMEOUT_MS;
        if (t > MAX_TIMEOUT_MS) return MAX_TIMEOUT_MS;
        return t;
    }
}
