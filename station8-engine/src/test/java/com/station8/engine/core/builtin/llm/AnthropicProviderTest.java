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
 * #342 — {@link AnthropicProvider} 회귀 가드. JDK 내장 {@link HttpServer} fixture (외부 인터넷 X).
 */
class AnthropicProviderTest {

    private HttpServer server;
    private int port;
    private JsonUtil jsonUtil;
    private AnthropicProvider provider;

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
        provider = new AnthropicProvider(jsonUtil, client);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private LlmProviderConfig config() {
        return new LlmProviderConfig("http://127.0.0.1:" + port, "sk-ant-test");
    }

    @Test
    void chat_extractsSystem_parsesTextAndUsage() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        server.createContext("/v1/messages", ex -> {
            apiKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            version.set(ex.getRequestHeaders().getFirst("anthropic-version"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = ("""
                    {"content":[{"type":"text","text":"안녕하세요"}],
                     "stop_reason":"end_turn",
                     "usage":{"input_tokens":12,"output_tokens":6}}
                    """).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
        });

        LlmRequest req = new LlmRequest("claude-sonnet-4",
                List.of(LlmMessage.system("be nice"), LlmMessage.user("hi")), null, null);
        LlmResponse response = provider.chat(req, config());

        assertThat(response.content()).isEqualTo("안녕하세요");
        assertThat(response.finishReason()).isEqualTo("end_turn");
        assertThat(response.usage().inputTokens()).isEqualTo(12);
        assertThat(response.usage().outputTokens()).isEqualTo(6);

        assertThat(apiKey.get()).isEqualTo("sk-ant-test");
        assertThat(version.get()).isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, Object> sent = jsonUtil.fromJson(body.get(), Map.class);
        assertThat(sent).containsEntry("system", "be nice");   // system은 top-level로 분리
        assertThat(sent).containsKey("max_tokens");             // 미지정 시 기본값
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) sent.get("messages");
        assertThat(messages).hasSize(1);                        // system 제외, user만
        assertThat(messages.get(0)).containsEntry("role", "user");
    }

    @Test
    void chat_parsesToolUseBlock() {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/messages", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = ("""
                    {"content":[
                       {"type":"text","text":"조회할게요"},
                       {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Seoul"}}],
                     "stop_reason":"tool_use",
                     "usage":{"input_tokens":20,"output_tokens":10}}
                    """).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
        });

        LlmRequest req = new LlmRequest("claude-sonnet-4", List.of(LlmMessage.user("weather?")),
                null, 512,
                List.of(new ToolDefinition("get_weather", "날씨",
                        Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))));
        LlmResponse response = provider.chat(req, config());

        assertThat(response.content()).isEqualTo("조회할게요");
        assertThat(response.finishReason()).isEqualTo("tool_use");
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("toolu_1");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).containsEntry("city", "Seoul");

        // tools는 input_schema 키로 직렬화 (OpenAI는 parameters)
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = jsonUtil.fromJson(body.get(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) sent.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsKey("input_schema");
        assertThat(sent).containsEntry("max_tokens", 512);
    }

    @Test
    void rateLimit_429_isRetryable() {
        server.createContext("/v1/messages", ex -> {
            byte[] b = "{\"error\":{\"message\":\"overloaded\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(429, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });

        assertThatThrownBy(() -> provider.chat(
                new LlmRequest("claude", List.of(LlmMessage.user("hi")), null, null), config()))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NoRetryException.class)
                .hasMessageContaining("rate limited");
    }

    @Test
    void badRequest_400_isNoRetry() {
        server.createContext("/v1/messages", ex -> {
            byte[] b = "{\"error\":{\"message\":\"invalid request\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(400, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });

        assertThatThrownBy(() -> provider.chat(
                new LlmRequest("claude", List.of(LlmMessage.user("hi")), null, null), config()))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("400");
    }
}
