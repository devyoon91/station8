package com.station8.app;

import com.station8.engine.core.DefaultLineContext;
import com.station8.engine.core.builtin.llm.AgenticLoopActivity;
import com.station8.engine.entity.Credential;
import com.station8.engine.crypto.CredentialCrypto;
import com.station8.engine.repository.CredentialRepository;
import com.station8.engine.util.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M23 (#345) — AI agent 데모 통합 E2E. 실제 엔진 빈을 통과해 agent 루프를 검증한다 (오프라인).
 *
 * <p>유닛 테스트(#339~#342)는 stub provider로만 돌렸다. 본 테스트는 실 빈 체인
 * (AgenticLoopActivity → LlmProviderResolver → OpenAiCompatibleProvider → 실 HTTP →
 * RegistryAgentToolExecutor → 등록된 {@code get_weather} 활동 → JDBC U_LLM_USAGE)을 한 번에 통과.</p>
 *
 * <p>외부 LLM 대신 JDK 내장 {@link HttpServer}로 OpenAI 호환 응답을 흉내낸다 — tool 결과 메시지가
 * 오면 stop, 아니면 {@code get_weather} 호출 요청. credential은 그 fixture를 가리키게 시드.</p>
 */
@SpringBootTest(classes = Application.class)
public class AgentDemoE2ETest {

    @DynamicPropertySource
    static void credentialKey(DynamicPropertyRegistry registry) {
        // AES-GCM 256-bit 데모 키 (테스트 전용).
        registry.add("station8.credential.key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JsonUtil jsonUtil;
    @Autowired AgenticLoopActivity agenticLoopActivity;
    @Autowired CredentialRepository credentialRepository;
    @Autowired CredentialCrypto crypto;

    private HttpServer llmServer;
    private int llmPort;

    @BeforeEach
    void setUp() throws IOException {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        populator.setContinueOnError(true);
        populator.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM H_LINE_LLM_USAGE");
        jdbcTemplate.execute("DELETE FROM U_LINE_CREDENTIAL WHERE NAME = 'demo-llm'");

        // OpenAI 호환 fixture — tool 결과가 대화에 있으면 최종 답, 없으면 get_weather 호출 요청.
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmPort = llmServer.getAddress().getPort();
        llmServer.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean hasToolResult = body.contains("\"role\":\"tool\"");
            byte[] resp = (hasToolResult ? finalResponse() : toolCallResponse())
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
        });
        llmServer.start();

        // demo-llm credential — fixture endpoint를 baseUrl로.
        String schemaJson = jsonUtil.toJson(Map.of("baseUrl", "http://127.0.0.1:" + llmPort + "/v1"));
        credentialRepository.insert(new Credential(
                UUID.randomUUID().toString(), "demo-llm", "openai_compatible",
                crypto.encrypt("demo-key"), schemaJson, "N", null, "test", null, null));
    }

    @AfterEach
    void tearDown() {
        if (llmServer != null) llmServer.stop(0);
        jdbcTemplate.execute("DELETE FROM U_LINE_CREDENTIAL WHERE NAME = 'demo-llm'");
    }

    private static String toolCallResponse() {
        return """
                {"choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"get_weather","arguments":"{\\"city\\":\\"Seoul\\"}"}}]},
                  "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":25,"completion_tokens":10}}
                """;
    }

    private static String finalResponse() {
        return """
                {"choices":[{"message":{"role":"assistant","content":"서울은 맑고 22도입니다."},
                  "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":40,"completion_tokens":12}}
                """;
    }

    @Test
    void agentLoop_callsToolThenAnswers_andRecordsUsage() {
        String instanceId = UUID.randomUUID().toString();
        String input = jsonUtil.toJson(Map.of(
                "credentialId", "demo-llm",
                "model", "gpt-4o-mini",
                "prompt", "What's the weather in Seoul?",
                "tools", List.of(Map.of(
                        "name", "get_weather",
                        "description", "도시의 현재 날씨 조회",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of("city", Map.of("type", "string")),
                                "required", List.of("city"))))));

        DefaultLineContext ctx = new DefaultLineContext(
                instanceId, "DemoLlmAgent", "llm.agent", 1, input, null, jsonUtil);

        String out = agenticLoopActivity.run(input, ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(out, Map.class);
        assertEquals("서울은 맑고 22도입니다.", parsed.get("content"));
        assertEquals("stop", parsed.get("stopReason"));
        assertEquals(2, parsed.get("iterations"), "tool 호출 1턴 + 최종 답 1턴 = 2 iterations");

        // 도구 실행 추적 — get_weather 1회, 에러 없음
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) parsed.get("steps");
        assertEquals(1, steps.size());
        assertEquals("get_weather", steps.get(0).get("tool"));
        assertEquals(false, steps.get(0).get("error"));
        // get_weather 활동이 실제 실행돼 도시/날씨가 결과에 들어가야
        assertTrue(String.valueOf(steps.get(0).get("result")).contains("Seoul"));
        assertTrue(String.valueOf(steps.get(0).get("result")).contains("Sunny"));

        // U_LLM_USAGE — iteration당 1건, 총 2건 기록
        Integer usageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM H_LINE_LLM_USAGE WHERE INSTANCE_ID = ?", Integer.class, instanceId);
        assertNotNull(usageCount);
        assertEquals(2, usageCount);
    }
}
