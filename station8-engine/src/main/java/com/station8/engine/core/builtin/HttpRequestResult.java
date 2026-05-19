package com.station8.engine.core.builtin;

import java.util.Map;

/**
 * {@link HttpRequestActivity}의 응답 shape — 활동 outputData에 JSON으로 저장된다.
 *
 * <h3>body 처리</h3>
 * <ul>
 *   <li>응답 content-type이 {@code application/json}이거나 {@code application/*+json}이면
 *       Jackson으로 parse 시도 → 성공 시 Object/List/Number 등으로 박힘, 실패 시 raw string</li>
 *   <li>그 외 content-type은 raw string 그대로</li>
 *   <li>body가 비어있으면 빈 string</li>
 * </ul>
 *
 * <h3>headers 처리</h3>
 * 키는 lowercase, 값이 multi-value면 comma-join. HTTP 헤더는 case-insensitive라 정규화한다.
 *
 * @param status  HTTP status code (2xx만 성공, 4xx는 NoRetryException, 5xx는 일반 retry)
 * @param headers 응답 헤더 (lowercase 키)
 * @param body    응답 본문 — Object (JSON parse 성공) 또는 String (raw)
 */
public record HttpRequestResult(
        int status,
        Map<String, String> headers,
        Object body
) {}
