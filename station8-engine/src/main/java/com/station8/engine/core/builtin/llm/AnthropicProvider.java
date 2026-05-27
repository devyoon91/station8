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
 * Anthropic Messages API provider (#342). OpenAI 호환군과 wire 포맷이 달라 별도 구현.
 *
 * <p>차이를 흡수하는 지점:</p>
 * <ul>
 *   <li>{@code system}이 top-level 필드 — system 메시지를 모아 분리</li>
 *   <li>{@code content}가 블록 배열 — text 블록 + tool_use 블록</li>
 *   <li>tool 결과는 user 메시지의 {@code tool_result} 블록으로 되먹임 ({@link LlmMessage#toolResult})</li>
 *   <li>tool 정의 키가 {@code input_schema} (OpenAI는 {@code parameters})</li>
 *   <li>인증 {@code x-api-key} 헤더 + {@code anthropic-version}</li>
 *   <li>{@code max_tokens} 필수 — 미지정 시 {@value #DEFAULT_MAX_TOKENS}</li>
 * </ul>
 *
 * <p>정규화 계약은 {@link OpenAiCompatibleProvider}와 동일 — {@link LlmResponse#toolCalls()}로 흡수.</p>
 */
@Component
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** Anthropic은 max_tokens 필수 — 미지정 시 기본값. */
    static final int DEFAULT_MAX_TOKENS = 1024;

    private final JsonUtil jsonUtil;
    private final HttpClient httpClient;

    @Autowired
    public AnthropicProvider(JsonUtil jsonUtil) {
        this(jsonUtil, defaultHttpClient());
    }

    /** 테스트 — HttpClient 주입. */
    AnthropicProvider(JsonUtil jsonUtil, HttpClient httpClient) {
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
        return "anthropic";
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
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(jsonUtil.toJson(body), StandardCharsets.UTF_8));
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.header("x-api-key", config.apiKey());
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

    /** {baseUrl}/v1/messages — 끝 슬래시 정규화. */
    private String endpoint(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed + "/v1/messages";
    }

    private Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS);

        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (LlmMessage m : request.messages()) {
            if ("system".equals(m.role())) {
                if (system.length() > 0) {
                    system.append("\n");
                }
                system.append(m.content() == null ? "" : m.content());
                continue;
            }
            messages.add(translateMessage(m));
        }
        if (system.length() > 0) {
            body.put("system", system.toString());
        }
        body.put("messages", messages);

        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", buildTools(request.tools()));
        }
        return body;
    }

    /** 정규화 LlmMessage → Anthropic 메시지. assistant 도구 호출 / tool 결과를 content 블록으로 번역. */
    private Map<String, Object> translateMessage(LlmMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        // tool 결과 메시지 → user 역할 + tool_result 블록 (#341 다중 턴)
        if ("tool".equals(m.role())) {
            out.put("role", "user");
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", m.toolCallId());
            block.put("content", m.content() == null ? "" : m.content());
            out.put("content", List.of(block));
            return out;
        }
        // assistant 도구 호출 → text 블록(있으면) + tool_use 블록들
        if ("assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            out.put("role", "assistant");
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (m.content() != null && !m.content().isEmpty()) {
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("type", "text");
                text.put("text", m.content());
                blocks.add(text);
            }
            for (ToolCall c : m.toolCalls()) {
                Map<String, Object> use = new LinkedHashMap<>();
                use.put("type", "tool_use");
                use.put("id", c.id());
                use.put("name", c.name());
                use.put("input", c.arguments() == null ? Map.of() : c.arguments());
                blocks.add(use);
            }
            out.put("content", blocks);
            return out;
        }
        // 일반 메시지 — content 문자열
        out.put("role", m.role());
        out.put("content", m.content() == null ? "" : m.content());
        return out;
    }

    /** ToolDefinition → Anthropic tools 포맷 ({@code [{name, description, input_schema}]}). */
    private List<Map<String, Object>> buildTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDefinition t : tools) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", t.name());
            if (t.description() != null) {
                tool.put("description", t.description());
            }
            tool.put("input_schema", t.parameters() != null ? t.parameters()
                    : Map.of("type", "object", "properties", Map.of()));
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
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (root.get("content") instanceof List<?> blocks) {
            for (Object o : blocks) {
                if (!(o instanceof Map<?, ?> block)) {
                    continue;
                }
                String type = String.valueOf(block.get("type"));
                if ("text".equals(type)) {
                    text.append(block.get("text") == null ? "" : block.get("text"));
                } else if ("tool_use".equals(type)) {
                    String id = block.get("id") == null ? null : String.valueOf(block.get("id"));
                    String name = block.get("name") == null ? null : String.valueOf(block.get("name"));
                    Map<String, Object> input = block.get("input") instanceof Map<?, ?> in
                            ? (Map<String, Object>) in : Map.of();
                    toolCalls.add(new ToolCall(id, name, input));
                }
            }
        }
        String finishReason = root.get("stop_reason") == null ? null : String.valueOf(root.get("stop_reason"));

        LlmUsage usage = LlmUsage.empty();
        if (root.get("usage") instanceof Map<?, ?> u) {
            usage = new LlmUsage(intValue(u.get("input_tokens")), intValue(u.get("output_tokens")));
        }
        return new LlmResponse(text.toString(), usage, finishReason, toolCalls);
    }

    /** 429 / 5xx / overloaded → retry, 그 외 4xx(context length 포함) → NoRetry. */
    private RuntimeException mapError(int status, String body) {
        String detail = extractErrorMessage(body);
        if (status == 429) {
            return new RuntimeException("LLM rate limited (429): " + detail);
        }
        if (status >= 500) {
            return new RuntimeException("LLM server error (" + status + "): " + detail);
        }
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
            // raw 그대로
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private boolean isContextLengthError(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("context")
                || lower.contains("too many tokens")
                || lower.contains("prompt is too long");
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
