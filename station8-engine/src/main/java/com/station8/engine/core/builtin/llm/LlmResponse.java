package com.station8.engine.core.builtin.llm;

import java.util.List;

/**
 * provider 중립 LLM 호출 응답 (#335).
 *
 * @param content      모델이 생성한 텍스트 (도구만 호출하고 텍스트 없으면 빈 문자열일 수 있음)
 * @param usage        토큰 사용량 (provider가 안 주면 {@link LlmUsage#empty()})
 * @param finishReason 종료 사유 (예: {@code stop}, {@code length}, {@code tool_calls}) — provider 원문
 * @param toolCalls    모델이 요청한 도구 호출 목록 (#340). 없으면 빈 목록
 */
public record LlmResponse(
        String content,
        LlmUsage usage,
        String finishReason,
        List<ToolCall> toolCalls
) {
    /** 도구 호출 없는 응답 단축 생성 (#339 호환). */
    public LlmResponse(String content, LlmUsage usage, String finishReason) {
        this(content, usage, finishReason, List.of());
    }
}
