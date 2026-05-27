package com.station8.engine.core.builtin.llm;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #342 — {@link LlmProviderResolver} 의 credential type → provider 선택 + baseUrl 해소 검증.
 */
class LlmProviderResolverTest {

    private StubCredentialResolver credentials;
    private LlmProviderResolver resolver;

    @BeforeEach
    void setUp() {
        JsonUtil jsonUtil = new JsonUtil();
        credentials = new StubCredentialResolver(jsonUtil);
        resolver = new LlmProviderResolver(credentials,
                new OpenAiCompatibleProvider(jsonUtil),
                new AnthropicProvider(jsonUtil));
    }

    @Test
    void openaiCompatible_selectsOpenAiProvider_withSchemaBaseUrl() {
        credentials.put("openai", new CredentialResolver.Resolved(
                "openai", "openai_compatible", "sk", Map.of("baseUrl", "http://ollama.internal/v1")));

        LlmProviderResolver.Resolved r = resolver.resolve("openai");
        assertThat(r.provider().name()).isEqualTo("openai-compatible");
        assertThat(r.config().baseUrl()).isEqualTo("http://ollama.internal/v1");
        assertThat(r.config().apiKey()).isEqualTo("sk");
    }

    @Test
    void anthropic_selectsAnthropicProvider_withDefaultBaseUrl() {
        credentials.put("claude", new CredentialResolver.Resolved(
                "claude", "anthropic", "sk-ant", Map.of()));

        LlmProviderResolver.Resolved r = resolver.resolve("claude");
        assertThat(r.provider().name()).isEqualTo("anthropic");
        assertThat(r.config().baseUrl()).isEqualTo("https://api.anthropic.com");
    }

    @Test
    void anthropic_honorsSchemaBaseUrlOverride() {
        credentials.put("claude", new CredentialResolver.Resolved(
                "claude", "anthropic", "sk-ant", Map.of("baseUrl", "http://proxy.internal")));

        assertThat(resolver.resolve("claude").config().baseUrl()).isEqualTo("http://proxy.internal");
    }

    @Test
    void openaiCompatible_missingBaseUrl_throwsNoRetry() {
        credentials.put("nobase", new CredentialResolver.Resolved(
                "nobase", "openai_compatible", "sk", Map.of()));

        assertThatThrownBy(() -> resolver.resolve("nobase"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void unsupportedType_throwsNoRetry() {
        credentials.put("bearer", new CredentialResolver.Resolved(
                "bearer", "http_bearer", "tok", Map.of()));

        assertThatThrownBy(() -> resolver.resolve("bearer"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("unsupported");
    }

    @Test
    void missingCredential_throwsNoRetry() {
        assertThatThrownBy(() -> resolver.resolve("ghost"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void blankCredentialId_throwsNoRetry() {
        assertThatThrownBy(() -> resolver.resolve("  "))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("required");
    }

    private static final class StubCredentialResolver extends CredentialResolver {
        private final Map<String, Resolved> store = new HashMap<>();

        StubCredentialResolver(JsonUtil jsonUtil) {
            super(null, null, jsonUtil);
        }

        void put(String name, Resolved r) {
            store.put(name, r);
        }

        @Override
        public Resolved resolveByName(String name) {
            return store.get(name);
        }
    }
}
