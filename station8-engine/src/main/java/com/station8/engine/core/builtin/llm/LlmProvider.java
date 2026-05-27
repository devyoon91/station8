package com.station8.engine.core.builtin.llm;

/**
 * LLM provider SPI (#335). single-shot non-streaming 호출 추상.
 *
 * <p>1차 구현은 {@link OpenAiCompatibleProvider} 하나 — OpenAI Chat Completions wire
 * 포맷을 따르는 OpenAI/Ollama/vLLM/LocalAI를 모두 덮는다. Anthropic은 wire 포맷이 달라
 * 별도 구현(#342)으로 붙는다.</p>
 *
 * <h3>에러 계약</h3>
 * <ul>
 *   <li>입력/인증/context length 초과 등 재시도 무의미 → {@link com.station8.engine.core.NoRetryException}</li>
 *   <li>429(rate limit) / 5xx / 네트워크 실패 → 일반 {@link RuntimeException} (엔진이 backoff 후 재시도)</li>
 * </ul>
 */
public interface LlmProvider {

    /**
     * provider 식별자 — {@code U_LLM_USAGE.PROVIDER} 컬럼과 로그에 기록.
     *
     * @return 예: {@code openai-compatible}
     */
    String name();

    /**
     * LLM을 호출하고 응답을 반환한다.
     *
     * @param request 모델/메시지/옵션
     * @param config  접속 정보 (baseUrl, apiKey)
     * @return 생성 텍스트 + 토큰 사용량
     */
    LlmResponse chat(LlmRequest request, LlmProviderConfig config);
}
