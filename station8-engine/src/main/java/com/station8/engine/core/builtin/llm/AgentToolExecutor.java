package com.station8.engine.core.builtin.llm;

import java.util.Map;

/**
 * AgenticLoop(#341)이 LLM의 도구 호출을 실제로 실행하는 추상.
 *
 * <p>호출 가능 범위(allowlist)는 AgenticLoop 노드 config의 {@code tools}가 정한다 — 본 executor는
 * "주어진 이름의 도구를 실행"만 한다. 실행 실패는 예외로 던지고, AgenticLoop이 잡아 에러 텍스트를
 * LLM에 되먹인다 (모델이 재시도/우회 판단).</p>
 */
public interface AgentToolExecutor {

    /**
     * 도구를 실행하고 결과 텍스트를 반환한다.
     *
     * @param toolName  도구 이름 (등록된 {@code @Activity} 이름에 매핑)
     * @param arguments 모델이 준 인자 (정규화된 object)
     * @return 실행 결과 — LLM에 되먹일 텍스트
     * @throws RuntimeException 미등록 도구이거나 실행이 실패한 경우
     */
    String execute(String toolName, Map<String, Object> arguments);
}
