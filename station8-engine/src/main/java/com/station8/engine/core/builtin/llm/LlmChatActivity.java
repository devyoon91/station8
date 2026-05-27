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
import java.util.List;
import java.util.Map;

/**
 * M23 built-in LLM 호출 활동 — {@code @Activity("llm.chat")} (#339).
 *
 * <p>RFC <a href="../../../../../../../../docs/decisions/m23-llm-provider-abstraction.md">m23-llm-provider-abstraction</a>
 * 결정 구현. OpenAI Chat Completions wire 포맷({@link OpenAiCompatibleProvider})으로 OpenAI +
 * 로컬 모델(Ollama/vLLM)을 한 경로로 호출하고, 토큰/비용을 H_LINE_LLM_USAGE에 기록한다.</p>
 *
 * <h3>credential</h3>
 * {@code credentialId}는 type {@code openai_compatible} credential을 가리킨다 —
 * {@code value}=apiKey, {@code schema.baseUrl}=endpoint. 로컬 모델은 apiKey 빈 값 허용.
 *
 * <h3>재시도</h3>
 * 429/5xx/네트워크 → 재시도, context length 초과/인증 오류/입력 오류 → {@link NoRetryException} 즉시 final-fail.
 * usage 기록 실패는 활동을 실패시키지 않는다 — LLM 호출은 이미 과금됐으므로 재시도 시 이중 과금 방지.
 */
@Component
@LineDefinition("LlmBuiltin")
public class LlmChatActivity {

    private static final Logger log = LoggerFactory.getLogger(LlmChatActivity.class);

    private static final String EXPECTED_CREDENTIAL_TYPE = "openai_compatible";

    private final JsonUtil jsonUtil;
    private final CredentialResolver credentialResolver;
    private final OpenAiCompatibleProvider provider;
    private final LlmCostCalculator costCalculator;
    private final LlmUsageRepository usageRepository;

    public LlmChatActivity(JsonUtil jsonUtil,
                           CredentialResolver credentialResolver,
                           OpenAiCompatibleProvider provider,
                           LlmCostCalculator costCalculator,
                           LlmUsageRepository usageRepository) {
        this.jsonUtil = jsonUtil;
        this.credentialResolver = credentialResolver;
        this.provider = provider;
        this.costCalculator = costCalculator;
        this.usageRepository = usageRepository;
    }

    @Activity(value = "llm.chat", retryCount = 2, backoffSeconds = 5,
            description = "LLM 호출 노드 — built-in. OpenAI 호환(OpenAI/Ollama/vLLM). model/prompt/credentialId 입력.",
            params = {
                @ActivityParam(name = "credentialId", kind = Kind.CREDENTIAL, required = true,
                        description = "provider 접속 credential (type openai_compatible). value=apiKey, schema.baseUrl=endpoint.",
                        options = {"openai_compatible"}),
                @ActivityParam(name = "model", kind = Kind.STRING, required = true,
                        description = "모델 식별자 (예: gpt-4o, llama3.1). 표현식 사용 가능."),
                @ActivityParam(name = "prompt", kind = Kind.STRING,
                        description = "단일 user 메시지. messages를 안 줄 때 사용."),
                @ActivityParam(name = "systemPrompt", kind = Kind.STRING,
                        description = "system 메시지 (prompt 모드)."),
                @ActivityParam(name = "messages", kind = Kind.OBJECT,
                        description = "[{role, content}] 명시 메시지 목록. prompt 대신."),
                @ActivityParam(name = "temperature", kind = Kind.NUMBER,
                        description = "샘플링 온도. 비우면 provider 기본값."),
                @ActivityParam(name = "maxTokens", kind = Kind.NUMBER,
                        description = "응답 최대 토큰. 비우면 provider 기본값."),
                @ActivityParam(name = "tools", kind = Kind.OBJECT,
                        description = "[{name, description, parameters(JSON Schema)}] 도구 목록. 모델이 호출 요청 시 outputData.toolCalls로 반환.")
            })
    public String chat(String inputJson, LineContext ctx) {
        LlmChatInput input = parseInput(inputJson);
        validate(input);

        LlmProviderConfig config = resolveProviderConfig(input.credentialId());
        LlmRequest request = new LlmRequest(
                input.model(), buildMessages(input), input.temperature(), input.maxTokens(), input.tools());

        LlmResponse response = provider.chat(request, config);

        BigDecimal cost = costCalculator.estimate(input.model(), response.usage());
        recordUsage(ctx, input.model(), response, cost);

        LlmChatResult result = new LlmChatResult(
                response.content(), input.model(), provider.name(),
                response.usage(), cost, response.finishReason(), response.toolCalls());
        return jsonUtil.toJson(result);
    }

    private LlmChatInput parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new NoRetryException("llm.chat input is empty");
        }
        try {
            return jsonUtil.fromJson(inputJson, LlmChatInput.class);
        } catch (Exception ex) {
            throw new NoRetryException("llm.chat input parse failed: " + ex.getMessage(), ex);
        }
    }

    private void validate(LlmChatInput input) {
        if (input.credentialId() == null || input.credentialId().isBlank()) {
            throw new NoRetryException("llm.chat credentialId is required");
        }
        if (input.model() == null || input.model().isBlank()) {
            throw new NoRetryException("llm.chat model is required");
        }
        boolean hasMessages = input.messages() != null && !input.messages().isEmpty();
        boolean hasPrompt = input.prompt() != null && !input.prompt().isBlank();
        if (!hasMessages && !hasPrompt) {
            throw new NoRetryException("llm.chat requires either messages or prompt");
        }
    }

    /** credentialId → provider 접속 config. type/baseUrl 검증. */
    private LlmProviderConfig resolveProviderConfig(String credentialId) {
        CredentialResolver.Resolved cred = credentialResolver.resolveByName(credentialId);
        if (cred == null) {
            throw new NoRetryException("llm.chat credentialId not found: " + credentialId);
        }
        if (!EXPECTED_CREDENTIAL_TYPE.equals(cred.type())) {
            throw new NoRetryException("llm.chat credential '" + credentialId
                    + "' must be type " + EXPECTED_CREDENTIAL_TYPE + " (got " + cred.type() + ")");
        }
        Object baseUrl = cred.schema().get("baseUrl");
        if (baseUrl == null || baseUrl.toString().isBlank()) {
            throw new NoRetryException("llm.chat credential '" + credentialId
                    + "' missing schema.baseUrl");
        }
        return new LlmProviderConfig(baseUrl.toString(), cred.value());
    }

    /** messages 우선, 없으면 systemPrompt + prompt로 구성. */
    private List<LlmMessage> buildMessages(LlmChatInput input) {
        if (input.messages() != null && !input.messages().isEmpty()) {
            List<LlmMessage> out = new ArrayList<>();
            for (Map<String, String> m : input.messages()) {
                String role = m.get("role");
                String content = m.get("content");
                if (role == null || role.isBlank()) {
                    throw new NoRetryException("llm.chat message missing role");
                }
                out.add(new LlmMessage(role, content == null ? "" : content));
            }
            return out;
        }
        List<LlmMessage> out = new ArrayList<>();
        if (input.systemPrompt() != null && !input.systemPrompt().isBlank()) {
            out.add(LlmMessage.system(input.systemPrompt()));
        }
        out.add(LlmMessage.user(input.prompt()));
        return out;
    }

    /** usage 기록 — 실패해도 활동은 성공 (이중 과금 방지). */
    private void recordUsage(LineContext ctx, String model, LlmResponse response, BigDecimal cost) {
        try {
            LlmUsageEntry entry = new LlmUsageEntry(
                    null,
                    ctx == null ? null : ctx.instanceId(),
                    ctx == null ? null : ctx.nodeId(),
                    ctx == null ? "llm.chat" : ctx.currentActivityName(),
                    provider.name(),
                    model,
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    cost,
                    promptHash(response),
                    null, null, "engine", null, null);
            usageRepository.insert(entry);
        } catch (Exception ex) {
            log.warn("llm.chat usage 기록 실패 (활동은 성공 처리) — model={}, {}: {}",
                    model, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /** 응답 content의 SHA-256 앞 16자 — 동일 응답 반복 탐지용 (식별자 아님). */
    private String promptHash(LlmResponse response) {
        String src = response.content() == null ? "" : response.content();
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
     * llm.chat outputData shape.
     *
     * @param content          모델 생성 텍스트 (도구만 호출하면 빈 문자열일 수 있음)
     * @param model            요청 모델
     * @param provider         provider 식별자
     * @param usage            토큰 사용량
     * @param estimatedCostUsd 추정 비용 (단가 미상 모델이면 null)
     * @param finishReason     종료 사유
     * @param toolCalls        모델이 요청한 도구 호출 목록 (#340). 없으면 빈 목록
     */
    public record LlmChatResult(
            String content,
            String model,
            String provider,
            LlmUsage usage,
            BigDecimal estimatedCostUsd,
            String finishReason,
            List<ToolCall> toolCalls
    ) {}
}
