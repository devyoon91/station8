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
 * #341 — {@link AgenticLoopActivity} 루프/도구 실행/안전장치 회귀 가드. in-memory 스텁.
 */
class AgenticLoopActivityTest {

    private JsonUtil jsonUtil;
    private StubCredentialResolver credentialResolver;
    private QueueProvider provider;
    private FakeToolExecutor toolExecutor;
    private FakeUsageRepository usageRepository;
    private AgenticLoopActivity activity;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        credentialResolver = new StubCredentialResolver(jsonUtil);
        provider = new QueueProvider(jsonUtil);
        toolExecutor = new FakeToolExecutor();
        usageRepository = new FakeUsageRepository();

        LlmCostProperties props = new LlmCostProperties();
        Map<String, LlmCostProperties.ModelPrice> pricing = new HashMap<>();
        pricing.put("gpt-4o", price("2.50", "10.00"));
        props.setPricing(pricing);
        LlmCostCalculator costCalculator = new LlmCostCalculator(props);

        activity = new AgenticLoopActivity(jsonUtil, credentialResolver, provider,
                costCalculator, usageRepository, toolExecutor);

        credentialResolver.put("openai", new CredentialResolver.Resolved(
                "openai", "openai_compatible", "sk", Map.of("baseUrl", "https://api.openai.com/v1")));
    }

    private static LlmCostProperties.ModelPrice price(String in, String out) {
        LlmCostProperties.ModelPrice p = new LlmCostProperties.ModelPrice();
        p.setInputPer1m(new BigDecimal(in));
        p.setOutputPer1m(new BigDecimal(out));
        return p;
    }

    private String input(Map<String, Object> extra) {
        Map<String, Object> in = new HashMap<>();
        in.put("credentialId", "openai");
        in.put("model", "gpt-4o");
        in.put("prompt", "weather in Seoul?");
        in.put("tools", List.of(Map.of("name", "get_weather", "description", "날씨",
                "parameters", Map.of("type", "object"))));
        in.putAll(extra);
        return jsonUtil.toJson(in);
    }

    private static LlmResponse toolCallResponse() {
        return new LlmResponse("", new LlmUsage(20, 5), "tool_calls",
                List.of(new ToolCall("call_1", "get_weather", Map.of("city", "Seoul"))));
    }

    private static LlmResponse stopResponse(String content) {
        return new LlmResponse(content, new LlmUsage(10, 8), "stop");
    }

    @Test
    void loop_executesToolThenStops() {
        provider.queued.add(toolCallResponse());
        provider.queued.add(stopResponse("Seoul is sunny, 22C"));
        toolExecutor.result = "맑음, 22도";

        String out = activity.run(input(Map.of()), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed).containsEntry("content", "Seoul is sunny, 22C");
        assertThat(parsed).containsEntry("iterations", 2);
        assertThat(parsed).containsEntry("stopReason", "stop");

        // steps: 도구 1회 실행
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) parsed.get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("tool", "get_weather");
        assertThat(steps.get(0)).containsEntry("result", "맑음, 22도");
        assertThat(steps.get(0)).containsEntry("error", false);

        // 2번째 LLM 호출에는 assistant tool_calls + tool 결과 메시지가 포함돼야
        LlmRequest second = provider.requests.get(1);
        assertThat(second.messages()).anyMatch(m -> "assistant".equals(m.role())
                && m.toolCalls() != null && !m.toolCalls().isEmpty());
        assertThat(second.messages()).anyMatch(m -> "tool".equals(m.role())
                && "call_1".equals(m.toolCallId()) && "맑음, 22도".equals(m.content()));

        // usage 2건 (iteration당)
        assertThat(usageRepository.entries).hasSize(2);
        // 도구 실제 호출됨
        assertThat(toolExecutor.calls).containsExactly("get_weather");
    }

    @Test
    void loop_maxIterations_stopsAtLimit() {
        provider.fallback = toolCallResponse();  // 항상 도구 호출 → 절대 stop 안 함

        String out = activity.run(input(Map.of("maxIterations", 3)), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed).containsEntry("iterations", 3);
        assertThat(parsed).containsEntry("stopReason", "max_iterations");
        assertThat(usageRepository.entries).hasSize(3);
    }

    @Test
    void loop_clampsToCapWhenModelNeverStops() {
        provider.fallback = toolCallResponse();

        String out = activity.run(input(Map.of("maxIterations", 999)), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed).containsEntry("iterations", AgenticLoopActivity.MAX_ITERATIONS_CAP);
        assertThat(parsed).containsEntry("stopReason", "max_iterations");
    }

    @Test
    void loop_toolNotAllowed_feedsErrorButContinues() {
        // 모델이 allowlist 밖 도구를 호출
        provider.queued.add(new LlmResponse("", new LlmUsage(20, 5), "tool_calls",
                List.of(new ToolCall("call_x", "delete_everything", Map.of()))));
        provider.queued.add(stopResponse("ok done"));

        String out = activity.run(input(Map.of()), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed).containsEntry("iterations", 2);
        assertThat(parsed).containsEntry("stopReason", "stop");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) parsed.get("steps");
        assertThat(steps.get(0)).containsEntry("error", true);
        assertThat((String) steps.get(0).get("result")).contains("not allowed");
        // 거부된 도구는 executor까지 가지 않음
        assertThat(toolExecutor.calls).isEmpty();
    }

    @Test
    void loop_toolExecutionFailure_feedsErrorButContinues() {
        provider.queued.add(toolCallResponse());
        provider.queued.add(stopResponse("recovered"));
        toolExecutor.failure = new RuntimeException("API down");

        String out = activity.run(input(Map.of()), new FakeLineContext());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertThat(parsed).containsEntry("iterations", 2);
        assertThat(parsed).containsEntry("stopReason", "stop");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) parsed.get("steps");
        assertThat(steps.get(0)).containsEntry("error", true);
        assertThat((String) steps.get(0).get("result")).contains("API down");
    }

    @Test
    void loop_missingCredential_throwsNoRetry() {
        assertThatThrownBy(() -> activity.run(input(Map.of("credentialId", "ghost")), new FakeLineContext()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void loop_missingPrompt_throwsNoRetry() {
        Map<String, Object> in = new HashMap<>();
        in.put("credentialId", "openai");
        in.put("model", "gpt-4o");
        assertThatThrownBy(() -> activity.run(jsonUtil.toJson(in), new FakeLineContext()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("prompt");
    }

    // ---- 스텁들 ----

    /** 응답 큐 + fallback. chat() 호출마다 큐에서 꺼내고, 비면 fallback 반환. */
    private static final class QueueProvider extends OpenAiCompatibleProvider {
        final List<LlmResponse> queued = new ArrayList<>();
        final List<LlmRequest> requests = new ArrayList<>();
        LlmResponse fallback;
        private int idx = 0;

        QueueProvider(JsonUtil jsonUtil) {
            super(jsonUtil);
        }

        @Override
        public LlmResponse chat(LlmRequest request, LlmProviderConfig config) {
            requests.add(request);
            if (idx < queued.size()) {
                return queued.get(idx++);
            }
            return fallback;
        }
    }

    private static final class FakeToolExecutor implements AgentToolExecutor {
        final List<String> calls = new ArrayList<>();
        String result = "(tool result)";
        RuntimeException failure;

        @Override
        public String execute(String toolName, Map<String, Object> arguments) {
            calls.add(toolName);
            if (failure != null) {
                throw failure;
            }
            return result;
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

        @Override
        public String insert(LlmUsageEntry entry) {
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
        @Override public String currentActivityName() { return "llm.agent"; }
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
