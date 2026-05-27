package com.station8.engine.core.builtin.llm;

import java.util.List;
import java.util.Map;

/**
 * {@link LlmChatActivity}의 입력 (#339). expression engine이 이미 풀어낸 final value.
 *
 * <p>메시지는 두 방식 중 하나로 준다:</p>
 * <ul>
 *   <li>{@code messages} — {@code [{"role":"user","content":"..."}]} 명시</li>
 *   <li>{@code prompt} (+ {@code systemPrompt}) — 단일 user 메시지 단축 표현</li>
 * </ul>
 *
 * @param credentialId provider 접속 credential 이름 (type {@code openai_compatible}). 필수
 * @param model        모델 식별자. 필수
 * @param messages     명시 메시지 목록. prompt와 둘 중 하나
 * @param prompt       단일 user 메시지 (messages 없을 때)
 * @param systemPrompt system 메시지 (prompt 모드에서만 사용)
 * @param temperature  샘플링 온도. null이면 provider 기본값
 * @param maxTokens    응답 최대 토큰. null이면 provider 기본값
 * @param tools        모델에 노출할 도구 목록 (#340). 모델이 호출을 요청하면 응답 toolCalls로 반환.
 *                     실제 실행/되먹임 반복은 AgenticLoop(#341) 담당 — 본 활동은 한 턴만
 */
public record LlmChatInput(
        String credentialId,
        String model,
        List<Map<String, String>> messages,
        String prompt,
        String systemPrompt,
        Double temperature,
        Integer maxTokens,
        List<ToolDefinition> tools
) {}
