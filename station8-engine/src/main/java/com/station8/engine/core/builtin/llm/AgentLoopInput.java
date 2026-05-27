package com.station8.engine.core.builtin.llm;

import java.util.List;

/**
 * {@link AgenticLoopActivity}의 입력 (#341).
 *
 * <p>{@code tools}는 두 역할을 겸한다: (1) 모델에 노출할 도구 목록, (2) 실행 allowlist —
 * 모델이 목록 밖 도구를 호출하면 거부된다. opt-in per node 보안.</p>
 *
 * @param credentialId  provider 접속 credential (type {@code openai_compatible}). 필수
 * @param model         모델 식별자. 필수
 * @param systemPrompt  system 메시지 (선택)
 * @param prompt        최초 user 메시지. 필수
 * @param tools         노출 + 실행 허용 도구 목록 (각 name은 등록된 @Activity)
 * @param maxIterations 최대 반복(LLM 호출) 횟수. null이면 기본값, 상한으로 클램프
 * @param temperature   샘플링 온도. null이면 provider 기본값
 * @param maxTokens     응답 최대 토큰. null이면 provider 기본값
 */
public record AgentLoopInput(
        String credentialId,
        String model,
        String systemPrompt,
        String prompt,
        List<ToolDefinition> tools,
        Integer maxIterations,
        Double temperature,
        Integer maxTokens
) {}
