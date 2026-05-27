package com.station8.engine.core.builtin.llm;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * credential 이름 → (provider, 접속 config) 해소 (#342).
 *
 * <p>credential의 type이 provider를 결정한다 — {@code openai_compatible} → {@link OpenAiCompatibleProvider},
 * {@code anthropic} → {@link AnthropicProvider}. llm.chat / llm.agent 활동이 공유 — credential 해소와
 * provider 선택 로직을 한 곳에 모은다 (#341에서 예고한 중복 제거).</p>
 */
@Component
public class LlmProviderResolver {

    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";

    private final CredentialResolver credentialResolver;
    private final Map<String, LlmProvider> providersByCredentialType;

    public LlmProviderResolver(CredentialResolver credentialResolver,
                               OpenAiCompatibleProvider openAiProvider,
                               AnthropicProvider anthropicProvider) {
        this.credentialResolver = credentialResolver;
        Map<String, LlmProvider> map = new LinkedHashMap<>();
        map.put("openai_compatible", openAiProvider);
        map.put("anthropic", anthropicProvider);
        this.providersByCredentialType = map;
    }

    /**
     * credential 이름으로 provider + 접속 config를 해소한다.
     *
     * @param credentialId vault credential 이름
     * @return provider + config
     * @throws NoRetryException credential 누락 / 미지원 type / baseUrl 누락
     */
    public Resolved resolve(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            throw new NoRetryException("llm credentialId is required");
        }
        CredentialResolver.Resolved cred = credentialResolver.resolveByName(credentialId);
        if (cred == null) {
            throw new NoRetryException("llm credentialId not found: " + credentialId);
        }
        LlmProvider provider = providersByCredentialType.get(cred.type());
        if (provider == null) {
            throw new NoRetryException("unsupported llm credential type: " + cred.type()
                    + " (supported: " + providersByCredentialType.keySet() + ")");
        }
        LlmProviderConfig config = new LlmProviderConfig(resolveBaseUrl(cred), cred.value());
        return new Resolved(provider, config);
    }

    /** baseUrl — schema.baseUrl 우선. anthropic은 기본값 fallback, openai_compatible은 필수. */
    private String resolveBaseUrl(CredentialResolver.Resolved cred) {
        Object baseUrl = cred.schema().get("baseUrl");
        if (baseUrl != null && !baseUrl.toString().isBlank()) {
            return baseUrl.toString();
        }
        if ("anthropic".equals(cred.type())) {
            return DEFAULT_ANTHROPIC_BASE_URL;
        }
        throw new NoRetryException("llm credential '" + cred.name() + "' missing schema.baseUrl");
    }

    /**
     * 해소 결과.
     *
     * @param provider 선택된 provider
     * @param config   접속 정보 (baseUrl, apiKey)
     */
    public record Resolved(LlmProvider provider, LlmProviderConfig config) {}
}
