package com.station8.engine.core.builtin.llm;

import java.util.List;

/**
 * provider 중립 LLM 호출 요청 (#335). single-shot, non-streaming.
 *
 * <p>tool calling은 본 RFC 범위 밖이라 필드에 없다 — #340에서 {@code tools} 추가 예정.</p>
 *
 * @param model       모델 식별자 (예: {@code gpt-4o}, 로컬 {@code llama3.1})
 * @param messages    대화 메시지 목록 (system/user/assistant 혼합). 비어 있으면 안 됨
 * @param temperature 샘플링 온도. null이면 provider 기본값
 * @param maxTokens   응답 최대 토큰. null이면 provider 기본값
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        Double temperature,
        Integer maxTokens
) {}
