package com.station8.engine.core.builtin.llm;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.LineContext;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.entity.LlmUsageEntry;
import com.station8.engine.repository.LlmUsageRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #339 — {@link LlmChatActivity} 회귀 가드. provider/credential/usage repo는 in-memory 스텁
 * (Mockito 5/ByteBuddy가 Java 25 클래스 mock 미지원).
 */
class LlmChatActivityTest {

    private JsonUtil jsonUtil;
    private StubCredentialResolver credentialResolver;
    private StubProvider provider;
    private FakeUsageRepository usageRepository;
    private LlmChatActivity activity;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        credentialResolver = new StubCredentialResolver(jsonUtil);
        provider = new StubProvider(jsonUtil);
        usageRepository = new FakeUsageRepository();

        LlmCostProperties props = new LlmCostProperties();
        Map<String, LlmCostProperties.ModelPrice> pricing = new HashMap<>();
        pricing.put("gpt-4o", price("2.50", "10.00"));
        props.setPricing(pricing);
        LlmCostCalculator costCalculator = new LlmCostCalculator(props);

        activity = new LlmChatActivity(jsonUtil, credentialResolver, provider, costCalculator, usageRepository);
    }

    private static LlmCostProperties.ModelPrice price(String in, String out) {
        LlmCostProperties.ModelPrice p = new LlmCostProperties.ModelPrice();
        p.setInputPer1m(new BigDecimal(in));
        p.setOutputPer1m(new BigDecimal(out));
        return p;
    }

    private void registerOpenAiCred(String name, String baseUrl) {
        credentialResolver.put(name, new CredentialResolver.Resolved(
                name, "openai_compatible", "sk-secret", Map.of("baseUrl", baseUrl)));
    }

    @Test
    void chat_happyPath_returnsContentAndRecordsUsage() {
        registerOpenAiCred("openai", "https://api.openai.com/v1");
        provider.next = new LlmResponse("the answer", new LlmUsage(1000, 500), "stop");

        String out = activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "openai",
                "model", "gpt-4o",
                "prompt", "what is 2+2?")), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed).containsEntry("content", "the answer");
        assertThat(parsed).containsEntry("model", "gpt-4o");
        assertThat(parsed).containsEntry("provider", "openai-compatible");
        assertThat(parsed).containsEntry("finishReason", "stop");

        // 비용: 2.50*1000/1M + 10.00*500/1M = 0.0025 + 0.005 = 0.0075
        assertThat(new BigDecimal(parsed.get("estimatedCostUsd").toString()))
                .isEqualByComparingTo("0.0075");

        // usage 기록
        assertThat(usageRepository.entries).hasSize(1);
        LlmUsageEntry e = usageRepository.entries.get(0);
        assertThat(e.instanceId()).isEqualTo("inst-1");
        assertThat(e.nodeId()).isEqualTo("node-1");
        assertThat(e.model()).isEqualTo("gpt-4o");
        assertThat(e.provider()).isEqualTo("openai-compatible");
        assertThat(e.inputTokens()).isEqualTo(1000);
        assertThat(e.outputTokens()).isEqualTo(500);
        assertThat(e.estimatedCostUsd()).isEqualByComparingTo("0.0075");
    }

    @Test
    void chat_messagesArray_buildsRequest() {
        registerOpenAiCred("openai", "https://api.openai.com/v1");
        provider.next = new LlmResponse("ok", new LlmUsage(3, 2), "stop");

        activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "openai",
                "model", "gpt-4o",
                "messages", List.of(
                        Map.of("role", "system", "content", "be terse"),
                        Map.of("role", "user", "content", "hi")))), new FakeLineContext());

        assertThat(provider.lastRequest.messages()).hasSize(2);
        assertThat(provider.lastRequest.messages().get(0).role()).isEqualTo("system");
        assertThat(provider.lastRequest.messages().get(1).content()).isEqualTo("hi");
    }

    @Test
    void chat_unknownModel_costNull_butUsageRecorded() {
        registerOpenAiCred("local", "http://ollama.internal:11434/v1");
        provider.next = new LlmResponse("yo", new LlmUsage(10, 20), "stop");

        String out = activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "local",
                "model", "llama3.1",
                "prompt", "hi")), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed.get("estimatedCostUsd")).isNull();
        assertThat(usageRepository.entries).hasSize(1);
        assertThat(usageRepository.entries.get(0).estimatedCostUsd()).isNull();
        assertThat(usageRepository.entries.get(0).outputTokens()).isEqualTo(20);
    }

    @Test
    void chat_wrongCredentialType_throwsNoRetry() {
        credentialResolver.put("bearer", new CredentialResolver.Resolved(
                "bearer", "http_bearer", "tok", Map.of()));

        assertThatThrownBy(() -> activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "bearer",
                "model", "gpt-4o",
                "prompt", "hi")), new FakeLineContext()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("openai_compatible");
    }

    @Test
    void chat_credentialMissingBaseUrl_throwsNoRetry() {
        credentialResolver.put("nobase", new CredentialResolver.Resolved(
                "nobase", "openai_compatible", "sk", Map.of()));

        assertThatThrownBy(() -> activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "nobase",
                "model", "gpt-4o",
                "prompt", "hi")), new FakeLineContext()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void chat_missingCredential_throwsNoRetry() {
        assertThatThrownBy(() -> activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "ghost",
                "model", "gpt-4o",
                "prompt", "hi")), new FakeLineContext()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void chat_noPromptNoMessages_throwsNoRetry() {
        registerOpenAiCred("openai", "https://api.openai.com/v1");
        assertThatThrownBy(() -> activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "openai",
                "model", "gpt-4o")), new FakeLineContext()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("messages or prompt");
    }

    @Test
    void chat_usageRecordingFailure_doesNotFailActivity() {
        registerOpenAiCred("openai", "https://api.openai.com/v1");
        provider.next = new LlmResponse("still ok", new LlmUsage(1, 1), "stop");
        usageRepository.failOnInsert = true;

        String out = activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "openai",
                "model", "gpt-4o",
                "prompt", "hi")), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed).containsEntry("content", "still ok");
    }

    // ---- 스텁들 ----

    /** chat()를 override해 네트워크 없이 canned 응답. */
    private static final class StubProvider extends OpenAiCompatibleProvider {
        LlmResponse next;
        LlmRequest lastRequest;

        StubProvider(JsonUtil jsonUtil) {
            super(jsonUtil);
        }

        @Override
        public LlmResponse chat(LlmRequest request, LlmProviderConfig config) {
            this.lastRequest = request;
            return next;
        }
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

    private static final class FakeUsageRepository implements LlmUsageRepository {
        final List<LlmUsageEntry> entries = new ArrayList<>();
        boolean failOnInsert = false;

        @Override
        public String insert(LlmUsageEntry entry) {
            if (failOnInsert) {
                throw new RuntimeException("simulated DB failure");
            }
            entries.add(entry);
            return "id-" + entries.size();
        }

        @Override
        public List<LlmUsageEntry> findByInstanceId(String instanceId) {
            return entries;
        }
    }

    /** 최소 LineContext — usage 기록에 필요한 instance/node/activity만 의미 있는 값. */
    private static final class FakeLineContext implements LineContext {
        @Override public String instanceId() { return "inst-1"; }
        @Override public String workflowName() { return "wf"; }
        @Override public String currentActivityName() { return "llm.chat"; }
        @Override public String nodeId() { return "node-1"; }
        @Override public int attempt() { return 1; }
        @Override public Object input() { return null; }
        @Override public Optional<Object> previousOutput() { return Optional.empty(); }
        @Override public Map<String, Object> attributes() { return Map.of(); }
        @Override public void setNext(String activityName, Object input) { }
        @Override public Optional<String> nextActivityName() { return Optional.empty(); }
        @Override public Optional<Object> nextActivityInput() { return Optional.empty(); }
        @Override public void saveState(Object stateSnapshot) { }
        @Override public Optional<Object> loadState() { return Optional.empty(); }
    }
}
