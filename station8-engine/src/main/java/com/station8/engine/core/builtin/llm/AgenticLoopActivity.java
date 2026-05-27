package com.station8.engine.core.builtin.llm;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.ActivityParam;
import com.station8.engine.annotation.ActivityParam.Kind;
import com.station8.engine.annotation.LineDefinition;
import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.LineContext;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.entity.LlmUsageEntry;
import com.station8.engine.repository.LlmUsageRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M23 agentic loop 컨트롤 노드 — {@code @Activity("llm.agent")} (#341).
 *
 * <p>"LLM 호출 → 도구 호출 요청 → 실행 → 결과 되먹임 → 다시 호출 → ... → 모델이 멈출 때까지"
 * 반복하는 agent 루프. n8n AI Agent 노드 포지션.</p>
 *
 * <h3>도구 실행 (allowlist)</h3>
 * 노드 config의 {@code tools}가 노출 + 실행 허용 목록을 겸한다. 모델이 목록 밖 도구를 호출하면
 * 거부 메시지를 결과로 되먹인다. 실행은 {@link AgentToolExecutor}에 위임 — 기본 구현은 도구 이름을
 * 등록된 {@code @Activity}로 매핑.
 *
 * <h3>실패/안전</h3>
 * <ul>
 *   <li>도구 실행 실패 → 에러 텍스트를 tool 결과로 되먹임 (모델이 재시도/우회/포기 판단). 루프는 계속</li>
 *   <li>{@code maxIterations} 안전장치 — 상한({@value #MAX_ITERATIONS_CAP})으로 클램프. 무한 루프 차단</li>
 *   <li>각 iteration의 LLM 호출마다 usage 기록 (누적 비용 가시성). 기록 실패는 활동을 죽이지 않음</li>
 * </ul>
 *
 * <p>credential 해소 / usage 기록 / promptHash 로직은 {@link LlmChatActivity}와 의도적으로 동일 —
 * provider 다양화 시 공통 헬퍼로 추출 예정.</p>
 */
@Component
@LineDefinition("LlmAgentBuiltin")
public class AgenticLoopActivity {

    private static final Logger log = LoggerFactory.getLogger(AgenticLoopActivity.class);

    private static final String EXPECTED_CREDENTIAL_TYPE = "openai_compatible";

    /** maxIterations 미지정 시 기본값. */
    static final int DEFAULT_MAX_ITERATIONS = 10;

    /** maxIterations 상한 — 그 이상은 클램프 (비용/시간 폭주 방지). */
    static final int MAX_ITERATIONS_CAP = 50;

    private final JsonUtil jsonUtil;
    private final CredentialResolver credentialResolver;
    private final OpenAiCompatibleProvider provider;
    private final LlmCostCalculator costCalculator;
    private final LlmUsageRepository usageRepository;
    private final AgentToolExecutor toolExecutor;

    public AgenticLoopActivity(JsonUtil jsonUtil,
                               CredentialResolver credentialResolver,
                               OpenAiCompatibleProvider provider,
                               LlmCostCalculator costCalculator,
                               LlmUsageRepository usageRepository,
                               AgentToolExecutor toolExecutor) {
        this.jsonUtil = jsonUtil;
        this.credentialResolver = credentialResolver;
        this.provider = provider;
        this.costCalculator = costCalculator;
        this.usageRepository = usageRepository;
        this.toolExecutor = toolExecutor;
    }

    @Activity(value = "llm.agent", retryCount = 1, backoffSeconds = 5,
            description = "AI agent 루프 노드 — built-in. LLM이 allowlist 도구를 호출↔결과 되먹임 반복.",
            params = {
                @ActivityParam(name = "credentialId", kind = Kind.CREDENTIAL, required = true,
                        description = "provider 접속 credential (type openai_compatible).",
                        options = {"openai_compatible"}),
                @ActivityParam(name = "model", kind = Kind.STRING, required = true,
                        description = "모델 식별자 (예: gpt-4o)."),
                @ActivityParam(name = "prompt", kind = Kind.STRING, required = true,
                        description = "최초 user 메시지."),
                @ActivityParam(name = "systemPrompt", kind = Kind.STRING,
                        description = "system 메시지 (선택)."),
                @ActivityParam(name = "tools", kind = Kind.OBJECT,
                        description = "[{name, description, parameters}] 노출+실행 허용 도구. name은 등록된 활동."),
                @ActivityParam(name = "maxIterations", kind = Kind.NUMBER,
                        description = "최대 반복 횟수. 기본 10, 상한 50."),
                @ActivityParam(name = "temperature", kind = Kind.NUMBER,
                        description = "샘플링 온도. 비우면 provider 기본값."),
                @ActivityParam(name = "maxTokens", kind = Kind.NUMBER,
                        description = "iteration당 응답 최대 토큰.")
            })
    public String run(String inputJson, LineContext ctx) {
        AgentLoopInput input = parseInput(inputJson);
        validate(input);

        LlmProviderConfig config = resolveProviderConfig(input.credentialId());
        int maxIterations = clampIterations(input.maxIterations());
        Set<String> allowed = allowedToolNames(input.tools());

        List<LlmMessage> messages = new ArrayList<>();
        if (input.systemPrompt() != null && !input.systemPrompt().isBlank()) {
            messages.add(LlmMessage.system(input.systemPrompt()));
        }
        messages.add(LlmMessage.user(input.prompt()));

        List<AgentStep> steps = new ArrayList<>();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        BigDecimal totalCost = null;
        String finalContent = "";
        String stopReason = "max_iterations";
        int iterations = 0;

        for (int i = 1; i <= maxIterations; i++) {
            iterations = i;
            LlmRequest request = new LlmRequest(
                    input.model(), messages, input.temperature(), input.maxTokens(), input.tools());
            LlmResponse response = provider.chat(request, config);

            BigDecimal cost = costCalculator.estimate(input.model(), response.usage());
            recordUsage(ctx, input.model(), response, cost);
            totalInputTokens += response.usage().inputTokens();
            totalOutputTokens += response.usage().outputTokens();
            totalCost = addCost(totalCost, cost);
            finalContent = response.content();

            if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                stopReason = "stop";
                break;
            }

            // 모델의 도구 호출을 대화에 다시 넣고, 각 도구를 실행해 결과를 되먹인다.
            messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                AgentStep step = executeTool(i, call, allowed);
                steps.add(step);
                messages.add(LlmMessage.toolResult(call.id(), step.result()));
            }
        }

        AgentLoopResult result = new AgentLoopResult(
                finalContent, iterations, stopReason,
                new LlmUsage(totalInputTokens, totalOutputTokens), totalCost, steps);
        return jsonUtil.toJson(result);
    }

    /** 도구 1건 실행 — allowlist 검사 후 executor 위임. 실패/거부는 에러 텍스트로 (LLM에 되먹임). */
    private AgentStep executeTool(int iteration, ToolCall call, Set<String> allowed) {
        if (call.name() == null || !allowed.contains(call.name())) {
            return new AgentStep(iteration, call.name(), call.arguments(),
                    "error: tool not allowed: " + call.name(), true);
        }
        try {
            String result = toolExecutor.execute(call.name(), call.arguments());
            return new AgentStep(iteration, call.name(), call.arguments(), result, false);
        } catch (Exception ex) {
            log.info("llm.agent tool '{}' 실행 실패 — 에러를 LLM에 되먹임: {}", call.name(), ex.getMessage());
            return new AgentStep(iteration, call.name(), call.arguments(),
                    "error: " + ex.getMessage(), true);
        }
    }

    private AgentLoopInput parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new NoRetryException("llm.agent input is empty");
        }
        try {
            return jsonUtil.fromJson(inputJson, AgentLoopInput.class);
        } catch (Exception ex) {
            throw new NoRetryException("llm.agent input parse failed: " + ex.getMessage(), ex);
        }
    }

    private void validate(AgentLoopInput input) {
        if (input.credentialId() == null || input.credentialId().isBlank()) {
            throw new NoRetryException("llm.agent credentialId is required");
        }
        if (input.model() == null || input.model().isBlank()) {
            throw new NoRetryException("llm.agent model is required");
        }
        if (input.prompt() == null || input.prompt().isBlank()) {
            throw new NoRetryException("llm.agent prompt is required");
        }
    }

    private int clampIterations(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_MAX_ITERATIONS;
        }
        return Math.min(requested, MAX_ITERATIONS_CAP);
    }

    private Set<String> allowedToolNames(List<ToolDefinition> tools) {
        Set<String> names = new HashSet<>();
        if (tools != null) {
            for (ToolDefinition t : tools) {
                if (t.name() != null) {
                    names.add(t.name());
                }
            }
        }
        return names;
    }

    private LlmProviderConfig resolveProviderConfig(String credentialId) {
        CredentialResolver.Resolved cred = credentialResolver.resolveByName(credentialId);
        if (cred == null) {
            throw new NoRetryException("llm.agent credentialId not found: " + credentialId);
        }
        if (!EXPECTED_CREDENTIAL_TYPE.equals(cred.type())) {
            throw new NoRetryException("llm.agent credential '" + credentialId
                    + "' must be type " + EXPECTED_CREDENTIAL_TYPE + " (got " + cred.type() + ")");
        }
        Object baseUrl = cred.schema().get("baseUrl");
        if (baseUrl == null || baseUrl.toString().isBlank()) {
            throw new NoRetryException("llm.agent credential '" + credentialId + "' missing schema.baseUrl");
        }
        return new LlmProviderConfig(baseUrl.toString(), cred.value());
    }

    private static BigDecimal addCost(BigDecimal total, BigDecimal delta) {
        if (delta == null) {
            return total;
        }
        return total == null ? delta : total.add(delta);
    }

    /** usage 기록 — 실패해도 루프는 계속 (이중 과금/중단 방지). */
    private void recordUsage(LineContext ctx, String model, LlmResponse response, BigDecimal cost) {
        try {
            LlmUsageEntry entry = new LlmUsageEntry(
                    null,
                    ctx == null ? null : ctx.instanceId(),
                    ctx == null ? null : ctx.nodeId(),
                    ctx == null ? "llm.agent" : ctx.currentActivityName(),
                    provider.name(),
                    model,
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    cost,
                    promptHash(response.content()),
                    null, null, "engine", null, null);
            usageRepository.insert(entry);
        } catch (Exception ex) {
            log.warn("llm.agent usage 기록 실패 (루프 계속) — model={}, {}: {}",
                    model, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private String promptHash(String content) {
        String src = content == null ? "" : content;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            return null;
        }
    }

    /**
     * 루프 한 스텝의 도구 호출 기록 (outputData 추적용).
     *
     * @param iteration 몇 번째 반복에서 호출됐나 (1-base)
     * @param tool      도구 이름
     * @param arguments 호출 인자
     * @param result    실행 결과 또는 에러 텍스트
     * @param error     실패/거부 여부
     */
    public record AgentStep(
            int iteration,
            String tool,
            Map<String, Object> arguments,
            String result,
            boolean error
    ) {}

    /**
     * llm.agent outputData shape.
     *
     * @param content       최종 모델 응답 텍스트
     * @param iterations    실제 LLM 호출 횟수
     * @param stopReason    {@code stop}(모델이 도구 없이 종료) 또는 {@code max_iterations}
     * @param totalUsage    전체 iteration 토큰 합
     * @param totalCostUsd  추정 총비용 (단가 미상 모델만 있으면 null)
     * @param steps         도구 호출 추적
     */
    public record AgentLoopResult(
            String content,
            int iterations,
            String stopReason,
            LlmUsage totalUsage,
            BigDecimal totalCostUsd,
            List<AgentStep> steps
    ) {}
}
