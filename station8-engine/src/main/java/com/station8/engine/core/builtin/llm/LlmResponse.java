package com.station8.engine.core.builtin.llm;

/**
 * provider 중립 LLM 호출 응답 (#335).
 *
 * @param content      모델이 생성한 텍스트
 * @param usage        토큰 사용량 (provider가 안 주면 {@link LlmUsage#empty()})
 * @param finishReason 종료 사유 (예: {@code stop}, {@code length}) — provider 원문 그대로
 */
public record LlmResponse(
        String content,
        LlmUsage usage,
        String finishReason
) {}
