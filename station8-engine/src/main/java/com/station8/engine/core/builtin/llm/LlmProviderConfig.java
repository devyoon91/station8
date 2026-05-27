package com.station8.engine.core.builtin.llm;

/**
 * LLM provider 접속 정보 — credential vault(M17)에서 해소한 값.
 *
 * <p>OpenAI 호환군은 baseUrl만 바꾸면 클라우드/로컬을 가른다. 사내 Ollama면
 * {@code http://ollama.internal:11434/v1}, 클라우드 OpenAI면 {@code https://api.openai.com/v1}.
 * 로컬 모델은 apiKey가 빈 값일 수 있다 (인증 없는 endpoint).</p>
 *
 * @param baseUrl API base URL (끝의 {@code /} 유무 무관 — provider가 정규화)
 * @param apiKey  API 키. blank면 Authorization 헤더 생략 (로컬 모델)
 */
public record LlmProviderConfig(String baseUrl, String apiKey) {}
