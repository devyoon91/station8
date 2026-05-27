package com.station8.engine.core.builtin.llm;

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
 * #339/#342 — {@link LlmChatActivity} 회귀 가드. provider 선택/credential 해소는 {@link LlmProviderResolver}로
 * 빠져, 본 테스트는 stub resolver로 provider만 주입한다 (credential 검증은 LlmProviderResolverTest).
 */
class LlmChatActivityTest {

    private JsonUtil jsonUtil;
    private StubProvider provider;
    private StubProviderResolver providerResolver;
    private FakeUsageRepository usageRepository;
    private LlmChatActivity activity;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        provider = new StubProvider(jsonUtil);
        providerResolver = new StubProviderResolver(provider);
        usageRepository = new FakeUsageRepository();

        LlmCostProperties props = new LlmCostProperties();
        Map<String, LlmCostProperties.ModelPrice> pricing = new HashMap<>();
        pricing.put("gpt-4o", price("2.50", "10.00"));
        props.setPricing(pricing);
        LlmCostCalculator costCalculator = new LlmCostCalculator(props);

        activity = new LlmChatActivity(jsonUtil, providerResolver, costCalculator, usageRepository);
    }

    private static LlmCostProperties.ModelPrice price(String in, String out) {
        LlmCostProperties.ModelPrice p = new LlmCostProperties.ModelPrice();
        p.setInputPer1m(new BigDecimal(in));
        p.setOutputPer1m(new BigDecimal(out));
        return p;
    }

    @Test
    void chat_happyPath_returnsContentAndRecordsUsage() {
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

        // 비용: 2.50*1000/1M + 10.00*500/1M = 0.0075
        assertThat(new BigDecimal(parsed.get("estimatedCostUsd").toString()))
                .isEqualByComparingTo("0.0075");

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
    void chat_passesToolsAndReturnsToolCalls() {
        provider.next = new LlmResponse("", new LlmUsage(20, 8), "tool_calls",
                List.of(new ToolCall("call_1", "get_weather", Map.of("city", "Seoul"))));

        String out = activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "openai",
                "model", "gpt-4o",
                "prompt", "weather in Seoul?",
                "tools", List.of(Map.of(
                        "name", "get_weather",
                        "description", "현재 날씨 조회",
                        "parameters", Map.of("type", "object",
                                "properties", Map.of("city", Map.of("type", "string"))))))),
                new FakeLineContext());

        assertThat(provider.lastRequest.tools()).hasSize(1);
        assertThat(provider.lastRequest.tools().get(0).name()).isEqualTo("get_weather");

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) parsed.get("toolCalls");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0)).containsEntry("name", "get_weather");
        assertThat(toolCalls.get(0)).containsEntry("id", "call_1");
    }

    @Test
    void chat_noPromptNoMessages_throwsNoRetry() {
        assertThatThrownBy(() -> activity.chat(jsonUtil.toJson(Map.of(
                "credentialId", "openai",
                "model", "gpt-4o")), new FakeLineContext()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("messages or prompt");
    }

    @Test
    void chat_usageRecordingFailure_doesNotFailActivity() {
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

    /** resolve()를 override해 항상 주어진 provider + 더미 config 반환. */
    private static final class StubProviderResolver extends LlmProviderResolver {
        private final LlmProvider provider;

        StubProviderResolver(LlmProvider provider) {
            super(null, null, null);
            this.provider = provider;
        }

        @Override
        public Resolved resolve(String credentialId) {
            return new Resolved(provider, new LlmProviderConfig("http://stub/v1", "k"));
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
