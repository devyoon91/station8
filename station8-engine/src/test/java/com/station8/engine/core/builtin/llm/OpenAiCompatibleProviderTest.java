package com.station8.engine.core.builtin.llm;

import com.station8.engine.core.NoRetryException;
import com.station8.engine.util.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #339 — {@link OpenAiCompatibleProvider} 회귀 가드. JDK 내장 {@link HttpServer} fixture (외부 인터넷 X).
 */
class OpenAiCompatibleProviderTest {

    private HttpServer server;
    private int port;
    private JsonUtil jsonUtil;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        jsonUtil = new JsonUtil();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        provider = new OpenAiCompatibleProvider(jsonUtil, client);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private LlmProviderConfig config() {
        return new LlmProviderConfig("http://127.0.0.1:" + port + "/v1", "sk-test");
    }

    private LlmRequest request() {
        return new LlmRequest("gpt-4o", List.of(LlmMessage.user("hello")), 0.7, 256);
    }

    @Test
    void chat_parsesContentAndUsage() {
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext("/v1/chat/completions", ex -> {
            captured.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("""
                    {"choices":[{"message":{"role":"assistant","content":"hi there"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":11,"completion_tokens":5}}
                    """).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        LlmResponse response = provider.chat(request(), config());

        assertThat(response.content()).isEqualTo("hi there");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage().inputTokens()).isEqualTo(11);
        assertThat(response.usage().outputTokens()).isEqualTo(5);

        // 요청 본문 검증 — model/messages/temperature/max_tokens
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = jsonUtil.fromJson(captured.get(), Map.class);
        assertThat(sent).containsEntry("model", "gpt-4o");
        assertThat(sent).containsKey("messages");
        assertThat(sent).containsKey("max_tokens");
    }

    @Test
    void rateLimit_429_isRetryable() {
        server.createContext("/v1/chat/completions", ex -> {
            byte[] body = "{\"error\":{\"message\":\"rate limit\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(429, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        assertThatThrownBy(() -> provider.chat(request(), config()))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NoRetryException.class)
                .hasMessageContaining("rate limited");
    }

    @Test
    void contextLengthExceeded_isNoRetry() {
        server.createContext("/v1/chat/completions", ex -> {
            byte[] body = ("{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\","
                    + "\"code\":\"context_length_exceeded\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(400, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        assertThatThrownBy(() -> provider.chat(request(), config()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("context length");
    }

    @Test
    void unauthorized_401_isNoRetry() {
        server.createContext("/v1/chat/completions", ex -> {
            byte[] body = "{\"error\":{\"message\":\"invalid api key\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        assertThatThrownBy(() -> provider.chat(request(), config()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("401");
    }

    @Test
    void serverError_5xx_isRetryable() {
        server.createContext("/v1/chat/completions", ex -> {
            ex.sendResponseHeaders(503, 0);
            ex.close();
        });

        assertThatThrownBy(() -> provider.chat(request(), config()))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NoRetryException.class)
                .hasMessageContaining("server error");
    }

    @Test
    void missingBaseUrl_isNoRetry() {
        assertThatThrownBy(() -> provider.chat(request(), new LlmProviderConfig("", "k")))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void chat_withTools_serializesAndParsesToolCalls() {
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext("/v1/chat/completions", ex -> {
            captured.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("""
                    {"choices":[{"message":{"role":"assistant","content":null,
                     "tool_calls":[{"id":"call_1","type":"function",
                       "function":{"name":"get_weather","arguments":"{\\"city\\":\\"Seoul\\"}"}}]},
                     "finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":20,"completion_tokens":8}}
                    """).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        LlmRequest req = new LlmRequest("gpt-4o", List.of(LlmMessage.user("weather in Seoul?")),
                null, null,
                List.of(new ToolDefinition("get_weather", "현재 날씨 조회",
                        Map.of("type", "object",
                                "properties", Map.of("city", Map.of("type", "string")),
                                "required", List.of("city")))));

        LlmResponse response = provider.chat(req, config());

        // content null → 빈 문자열로 코어싱
        assertThat(response.content()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("tool_calls");
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).containsEntry("city", "Seoul");

        // 요청 본문에 tools 직렬화 확인
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = jsonUtil.fromJson(captured.get(), Map.class);
        assertThat(sent).containsKey("tools");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) sent.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsEntry("type", "function");
    }
}
