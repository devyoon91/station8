package com.station8.engine.core.builtin.llm;

/**
 * LLM 대화 메시지 한 건 — provider 중립 형태.
 *
 * <p>OpenAI/Anthropic 모두 role + content 구조를 공유하므로 공통 모델로 둔다.
 * tool 호출/결과 메시지는 본 RFC(#335) 범위 밖 — tool calling sub-issue(#340)에서 확장.</p>
 *
 * @param role    {@code system} / {@code user} / {@code assistant}
 * @param content 메시지 본문 (텍스트)
 */
public record LlmMessage(String role, String content) {

    /** user 메시지 단축 생성. */
    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    /** system 메시지 단축 생성. */
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }
}
