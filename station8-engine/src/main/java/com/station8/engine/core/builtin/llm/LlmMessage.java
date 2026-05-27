package com.station8.engine.core.builtin.llm;

import java.util.List;

/**
 * LLM 대화 메시지 한 건 — provider 중립 형태.
 *
 * <p>AgenticLoop(#341)의 다중 턴을 위해 도구 호출/결과 메시지까지 표현한다:</p>
 * <ul>
 *   <li>일반 메시지 — {@code (role, content)}, toolCalls/toolCallId null</li>
 *   <li>assistant 도구 호출 — {@code role=assistant} + {@code toolCalls} (모델이 호출 요청한 것)</li>
 *   <li>tool 결과 — {@code role=tool} + {@code toolCallId} + content(실행 결과)</li>
 * </ul>
 *
 * @param role       {@code system} / {@code user} / {@code assistant} / {@code tool}
 * @param content    메시지 본문 (텍스트). 도구만 호출하는 assistant 메시지는 빈 문자열일 수 있음
 * @param toolCalls  assistant 메시지가 요청한 도구 호출 (#341). 일반 메시지는 null
 * @param toolCallId tool 결과 메시지가 응답하는 호출 id (#341). 일반 메시지는 null
 */
public record LlmMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {

    /** 일반 (role, content) 메시지 단축 생성. */
    public LlmMessage(String role, String content) {
        this(role, content, null, null);
    }

    /** user 메시지 단축 생성. */
    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    /** system 메시지 단축 생성. */
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    /** assistant 도구 호출 메시지 (#341) — 다중 턴에서 모델의 호출을 다시 보낼 때. */
    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        return new LlmMessage("assistant", content, toolCalls, null);
    }

    /** tool 결과 메시지 (#341) — 도구 실행 결과를 모델에 되먹임. */
    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, null, toolCallId);
    }
}
