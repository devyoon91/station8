package com.station8.engine.core.builtin.llm;

import java.util.List;

/**
 * provider 중립 LLM 호출 요청 (#335). single-shot, non-streaming.
 *
 * @param model       모델 식별자 (예: {@code gpt-4o}, 로컬 {@code llama3.1})
 * @param messages    대화 메시지 목록 (system/user/assistant 혼합). 비어 있으면 안 됨
 * @param temperature 샘플링 온도. null이면 provider 기본값
 * @param maxTokens   응답 최대 토큰. null이면 provider 기본값
 * @param tools       모델에 노출할 도구 목록 (#340). null/빈 목록이면 도구 없이 호출
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        Double temperature,
        Integer maxTokens,
        List<ToolDefinition> tools
) {
    /** 도구 없는 호출 단축 생성 (#339 호환). */
    public LlmRequest(String model, List<LlmMessage> messages, Double temperature, Integer maxTokens) {
        this(model, messages, temperature, maxTokens, null);
    }
}
