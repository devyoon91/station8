package com.station8.engine.core.builtin.llm;

import com.station8.engine.core.NoRetryException;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI Chat Completions wire 포맷 provider (#335, #339).
 *
 * <p>OpenAI 자체뿐 아니라 Ollama/vLLM/LocalAI/Azure OpenAI가 모두 이 포맷의 호환 endpoint를
 * 노출하므로, baseUrl만 바꿔 클라우드와 폐쇄망 로컬 모델을 한 코드 경로로 호출한다.</p>
 *
 * <h3>SSRF 정책</h3>
 * HTTP 활동(http.request)과 달리 {@code NetworkPolicy}를 적용하지 않는다 — 사내 Ollama 같은
 * 내부 endpoint 호출이 1급 시나리오이기 때문. baseUrl은 운영자가 vault credential로 관리하므로
 * 임의 사용자 입력이 아니다.
 *
 * <h3>에러 매핑</h3>
 * <ul>
 *   <li>context length 초과 (400, code/message 패턴) → {@link NoRetryException}</li>
 *   <li>그 외 4xx (인증/요청 오류) → {@link NoRetryException}</li>
 *   <li>429 / 5xx / I/O / timeout → 일반 {@link RuntimeException} (엔진 재시도)</li>
 * </ul>
 */
@Component
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final JsonUtil jsonUtil;
    private final HttpClient httpClient;

    @Autowired
    public OpenAiCompatibleProvider(JsonUtil jsonUtil) {
        this(jsonUtil, defaultHttpClient());
    }

    /** 테스트 — HttpClient 주입. */
    OpenAiCompatibleProvider(JsonUtil jsonUtil, HttpClient httpClient) {
        this.jsonUtil = jsonUtil;
        this.httpClient = httpClient;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    @Override
    public LlmResponse chat(LlmRequest request, LlmProviderConfig config) {
        validate(request, config);

        Map<String, Object> body = buildRequestBody(request);
        String url = endpoint(config.baseUrl());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonUtil.toJson(body), StandardCharsets.UTF_8));
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException("LLM I/O failure: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM call interrupted", ex);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parseSuccess(response.body());
        }
        throw mapError(status, response.body());
    }

    private void validate(LlmRequest request, LlmProviderConfig config) {
        if (config == null || config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new NoRetryException("llm provider baseUrl is required");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new NoRetryException("llm model is required");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new NoRetryException("llm messages must not be empty");
        }
    }

    /** {baseUrl}/chat/completions — 끝 슬래시 정규화. */
    private String endpoint(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed + "/chat/completions";
    }

    private Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        List<Map<String, String>> messages = new ArrayList<>();
        for (LlmMessage m : request.messages()) {
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", m.content() == null ? "" : m.content());
            messages.add(msg);
        }
        body.put("messages", messages);
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", buildTools(request.tools()));
        }
        return body;
    }

    /** ToolDefinition → OpenAI tools 포맷 ({@code [{type:function, function:{name, description, parameters}}]}). */
    private List<Map<String, Object>> buildTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDefinition t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            if (t.description() != null) {
                fn.put("description", t.description());
            }
            fn.put("parameters", t.parameters() != null ? t.parameters()
                    : Map.of("type", "object", "properties", Map.of()));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            out.add(tool);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private LlmResponse parseSuccess(String raw) {
        Map<String, Object> root;
        try {
            root = jsonUtil.fromJson(raw, Map.class);
        } catch (Exception ex) {
            throw new RuntimeException("LLM response parse failed: " + ex.getMessage(), ex);
        }
        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new RuntimeException("LLM response has no choices");
        }
        Map<String, Object> first = (Map<String, Object>) choices.get(0);
        Map<String, Object> message = (Map<String, Object>) first.get("message");
        // 도구만 호출하는 응답은 content가 null일 수 있다 — "null" 문자열로 새지 않게 코어싱.
        Object contentObj = message == null ? null : message.get("content");
        String content = contentObj == null ? "" : String.valueOf(contentObj);
        String finishReason = first.get("finish_reason") == null
                ? null : String.valueOf(first.get("finish_reason"));
        List<ToolCall> toolCalls = parseToolCalls(message);

        LlmUsage usage = LlmUsage.empty();
        if (root.get("usage") instanceof Map<?, ?> u) {
            usage = new LlmUsage(intValue(u.get("prompt_tokens")), intValue(u.get("completion_tokens")));
        }
        return new LlmResponse(content, usage, finishReason, toolCalls);
    }

    /** OpenAI {@code message.tool_calls[]} → 정규화 {@link ToolCall} 목록. arguments(JSON 문자열)는 object로 파싱. */
    @SuppressWarnings("unchecked")
    private List<ToolCall> parseToolCalls(Map<String, Object> message) {
        if (message == null || !(message.get("tool_calls") instanceof List<?> rawCalls)) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (Object o : rawCalls) {
            if (!(o instanceof Map<?, ?> call)) {
                continue;
            }
            String id = call.get("id") == null ? null : String.valueOf(call.get("id"));
            if (!(call.get("function") instanceof Map<?, ?> fn)) {
                continue;
            }
            String name = fn.get("name") == null ? null : String.valueOf(fn.get("name"));
            calls.add(new ToolCall(id, name, parseArguments(fn.get("arguments"))));
        }
        return calls;
    }

    /** OpenAI는 arguments를 JSON 문자열로 준다 — object로 파싱. 일부 호환 구현은 이미 object. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object arguments) {
        if (arguments instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (arguments instanceof String s && !s.isBlank()) {
            try {
                Map<String, Object> parsed = jsonUtil.fromJson(s, Map.class);
                return parsed != null ? parsed : Map.of();
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    /** status별 예외 매핑. 4xx 중 context length 초과/요청 오류는 NoRetry, 429/5xx는 retry. */
    private RuntimeException mapError(int status, String body) {
        String detail = extractErrorMessage(body);
        if (status == 429) {
            return new RuntimeException("LLM rate limited (429): " + detail);
        }
        if (status >= 500) {
            return new RuntimeException("LLM server error (" + status + "): " + detail);
        }
        // 4xx
        if (isContextLengthError(body)) {
            return new NoRetryException("LLM context length exceeded: " + detail);
        }
        return new NoRetryException("LLM client error (" + status + "): " + detail);
    }

    @SuppressWarnings("unchecked")
    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        try {
            Map<String, Object> root = jsonUtil.fromJson(body, Map.class);
            if (root.get("error") instanceof Map<?, ?> err && err.get("message") != null) {
                return String.valueOf(err.get("message"));
            }
        } catch (Exception ignored) {
            // raw 그대로 — 단, 과도하게 길면 자름
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private boolean isContextLengthError(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("context_length_exceeded")
                || lower.contains("maximum context length")
                || lower.contains("context window");
    }

    private static int intValue(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
