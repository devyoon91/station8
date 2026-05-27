package com.station8.engine.core.builtin.llm;

import java.util.Map;

/**
 * 모델이 요청한 도구 호출 1건 — provider 응답에서 정규화 (#340).
 *
 * <p>provider별 원문이 다르지만 공통 형태로 흡수:</p>
 * <ul>
 *   <li>OpenAI — {@code message.tool_calls[].function} 의 {@code name} + {@code arguments}(JSON 문자열) 파싱</li>
 *   <li>Anthropic — {@code content[].type=="tool_use"} 의 {@code name} + {@code input}(이미 object)</li>
 * </ul>
 *
 * <p>실제 도구 실행과 결과 되먹임은 AgenticLoop(#341)이 담당 — 본 모델은 "무엇을 호출하라 했나"만 표현.</p>
 *
 * @param id        호출 식별자 (결과를 되먹일 때 매칭용). provider가 안 주면 null
 * @param name      호출 대상 도구 이름
 * @param arguments 인자 (파싱된 object). 파싱 불가/없으면 빈 Map
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {}
