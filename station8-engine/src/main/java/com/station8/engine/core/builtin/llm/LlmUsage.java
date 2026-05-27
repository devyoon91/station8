package com.station8.engine.core.builtin.llm;

/**
 * LLM 호출 1건의 토큰 사용량 — provider 응답의 usage 필드에서 정규화.
 *
 * @param inputTokens  프롬프트(입력) 토큰 수
 * @param outputTokens 응답(출력) 토큰 수
 */
public record LlmUsage(int inputTokens, int outputTokens) {

    /** usage 정보를 못 받은 경우의 빈 사용량 (0/0). */
    public static LlmUsage empty() {
        return new LlmUsage(0, 0);
    }
}
