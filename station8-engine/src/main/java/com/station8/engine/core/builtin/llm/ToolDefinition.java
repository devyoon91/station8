package com.station8.engine.core.builtin.llm;

import java.util.Map;

/**
 * LLM에 노출하는 도구(tool) 정의 — provider 중립 (#340).
 *
 * <p>OpenAI와 Anthropic 모두 도구 인자를 JSON Schema로 받으므로 공통 형태로 둔다. provider별
 * wire 차이는 {@link LlmProvider} 구현이 흡수한다 (OpenAI는 {@code tools[].function}, Anthropic은
 * {@code tools[]} top-level).</p>
 *
 * @param name        도구 이름 (모델이 호출 시 지칭)
 * @param description 도구 용도 설명 — 모델이 언제 쓸지 판단하는 근거
 * @param parameters  인자 JSON Schema object (예: {@code {"type":"object","properties":{...},"required":[...]}}).
 *                    인자 없는 도구면 빈 schema 또는 null
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {}
