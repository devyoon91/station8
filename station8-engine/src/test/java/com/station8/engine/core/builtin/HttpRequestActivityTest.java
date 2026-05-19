package com.station8.engine.core.builtin;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.core.builtin.network.HostResolver;
import com.station8.engine.core.builtin.network.NetworkPolicy;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #288 — built-in HTTP 활동 회귀 가드.
 *
 * <p>로컬 {@link HttpServer} (JDK 내장, 의존성 0) 로 fixture 운영. 외부 인터넷 X.
 * 각 테스트는 자체 endpoint 등록 후 활동 호출 → 응답 shape / 예외 타입 검증.</p>
 *
 * <p>{@link CredentialResolver}는 Mockito 대신 in-memory 스텁으로 — Mockito 5/ByteBuddy가 Java 25
 * 클래스 파일을 지원 안 해서 mock(Class)가 막힘. 어차피 lookup 1개만 stub하면 되는 단순 케이스다.</p>
 */
class HttpRequestActivityTest {

    private HttpServer server;
    private int port;
    private JsonUtil jsonUtil;
    private StubCredentialResolver credentialResolver;
    private HttpRequestActivity activity;
    private final List<RecordedRequest> recorded = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        recorded.clear();

        jsonUtil = new JsonUtil();
        credentialResolver = new StubCredentialResolver(jsonUtil);
        // 본 테스트는 활동 로직 자체에 집중 — SSRF 정책은 #289 별도 테스트가 cover.
        // 여기선 permissive 모드로 127.0.0.1 fixture 호출이 통과되게.
        NetworkPolicy permissive = new NetworkPolicy("permissive", "", false, HostResolver.DEFAULT);
        permissive.init();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        activity = new HttpRequestActivity(jsonUtil, credentialResolver, permissive, client);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ============ 기본 GET ============

    @Test
    void get_returnsStatusHeadersAndJsonBody() {
        server.createContext("/api", ex -> {
            recorded.add(new RecordedRequest(ex.getRequestMethod(),
                    "x-test", ex.getRequestHeaders().getFirst("X-Test"), null));
            byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        String result = activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/api",
                "headers", Map.of("X-Test", "alpha"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(result, Map.class);
        assertThat(parsed).containsEntry("status", 200);
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) parsed.get("headers");
        assertThat(headers).containsEntry("content-type", "application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parsed.get("body");
        assertThat(body).containsEntry("hello", "world");

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).method).isEqualTo("GET");
        assertThat(recorded.get(0).headerValue).isEqualTo("alpha");
    }

    @Test
    void get_nonJsonBody_returnsRawString() {
        server.createContext("/text", ex -> {
            byte[] body = "plain text response".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });

        String result = activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/text")));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(result, Map.class);
        assertThat(parsed.get("body")).isEqualTo("plain text response");
    }

    // ============ POST body ============

    @Test
    void post_jsonBody_serializesAndSendsContentType() {
        server.createContext("/post", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            recorded.add(new RecordedRequest(ex.getRequestMethod(), "content-type", ct, body));
            ex.sendResponseHeaders(201, 0);
            ex.close();
        });

        Map<String, Object> jsonBody = new HashMap<>();
        jsonBody.put("name", "alice");
        jsonBody.put("age", 30);
        activity.request(jsonUtil.toJson(Map.of(
                "method", "POST",
                "url", "http://127.0.0.1:" + port + "/post",
                "body", jsonBody)));

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).method).isEqualTo("POST");
        assertThat(recorded.get(0).headerValue).isEqualTo("application/json");
        assertThat(recorded.get(0).body).contains("\"name\":\"alice\"").contains("\"age\":30");
    }

    @Test
    void post_stringBody_sentAsIs() {
        server.createContext("/raw", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            recorded.add(new RecordedRequest(ex.getRequestMethod(), "content-type", ct, body));
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });

        activity.request(jsonUtil.toJson(Map.of(
                "method", "POST",
                "url", "http://127.0.0.1:" + port + "/raw",
                "headers", Map.of("Content-Type", "text/plain"),
                "body", "<raw payload>")));

        assertThat(recorded.get(0).body).isEqualTo("<raw payload>");
        // 사용자가 명시한 헤더가 우선 — 자동 application/json 안 박힘
        assertThat(recorded.get(0).headerValue).isEqualTo("text/plain");
    }

    // ============ 4xx → NoRetryException ============

    @Test
    void status4xx_throwsNoRetryException() {
        server.createContext("/bad", ex -> {
            ex.sendResponseHeaders(404, 0);
            ex.close();
        });

        assertThatThrownBy(() -> activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/bad"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void status5xx_throwsPlainRuntimeException_forRetry() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/oops", ex -> {
            calls.incrementAndGet();
            ex.sendResponseHeaders(503, 0);
            ex.close();
        });

        assertThatThrownBy(() -> activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/oops"))))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NoRetryException.class)
                .hasMessageContaining("HTTP 503");
        assertThat(calls.get()).isEqualTo(1);  // 활동 자체는 1회 — 재시도는 엔진이
    }

    // ============ 입력 검증 ============

    @Test
    void missingMethod_throwsNoRetry() {
        assertThatThrownBy(() -> activity.request(jsonUtil.toJson(Map.of(
                "url", "http://127.0.0.1:" + port + "/x"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("method is required");
    }

    @Test
    void unsupportedMethod_throwsNoRetry() {
        assertThatThrownBy(() -> activity.request(jsonUtil.toJson(Map.of(
                "method", "TRACE",
                "url", "http://127.0.0.1:" + port + "/x"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void relativeUrl_throwsNoRetry() {
        assertThatThrownBy(() -> activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "/relative/path"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void fileScheme_throwsNoRetry() {
        assertThatThrownBy(() -> activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "file:///etc/passwd"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("http or https");
    }

    // ============ credential 자동 헤더 ============

    @Test
    void credential_bearer_injectsAuthorization() {
        credentialResolver.put("slack",
                new CredentialResolver.Resolved("slack", "http_bearer", "xoxb-secret", Map.of()));

        server.createContext("/auth", ex -> {
            recorded.add(new RecordedRequest(ex.getRequestMethod(),
                    "authorization", ex.getRequestHeaders().getFirst("Authorization"), null));
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });

        activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/auth",
                "credentialId", "slack")));

        assertThat(recorded.get(0).headerValue).isEqualTo("Bearer xoxb-secret");
    }

    @Test
    void credential_basic_injectsBase64Authorization() {
        credentialResolver.put("svc",
                new CredentialResolver.Resolved("svc", "http_basic", "secretpw",
                        Map.of("username", "alice")));

        server.createContext("/basic", ex -> {
            recorded.add(new RecordedRequest(ex.getRequestMethod(),
                    "authorization", ex.getRequestHeaders().getFirst("Authorization"), null));
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });

        activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/basic",
                "credentialId", "svc")));

        // base64("alice:secretpw") = "YWxpY2U6c2VjcmV0cHc="
        assertThat(recorded.get(0).headerValue).isEqualTo("Basic YWxpY2U6c2VjcmV0cHc=");
    }

    @Test
    void credential_apiKey_injectsCustomHeader() {
        credentialResolver.put("ak",
                new CredentialResolver.Resolved("ak", "api_key", "abc123",
                        Map.of("header", "X-API-Key")));

        server.createContext("/api-key", ex -> {
            recorded.add(new RecordedRequest(ex.getRequestMethod(),
                    "x-api-key", ex.getRequestHeaders().getFirst("X-API-Key"), null));
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });

        activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/api-key",
                "credentialId", "ak")));

        assertThat(recorded.get(0).headerValue).isEqualTo("abc123");
    }

    @Test
    void credential_missing_throwsNoRetry() {
        assertThatThrownBy(() -> activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/x",
                "credentialId", "nope"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("credentialId not found");
    }

    // ============ NetworkPolicy 통합 (#289) ============

    @Test
    void networkPolicyViolation_failsAsNoRetry() {
        // 활동을 blocklist 정책으로 교체. 127.0.0.1 fixture는 loopback이라 차단되어야.
        NetworkPolicy blockLoopback = new NetworkPolicy("", "", false, HostResolver.DEFAULT);
        blockLoopback.init();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequestActivity blocked = new HttpRequestActivity(
                jsonUtil, credentialResolver, blockLoopback, client);

        // fixture endpoint는 안 만들어도 됨 — 정책 검증이 실제 호출 전에 throw하므로
        assertThatThrownBy(() -> blocked.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/anything"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void userHeaderOverridesCredentialHeader() {
        credentialResolver.put("c",
                new CredentialResolver.Resolved("c", "http_bearer", "auto-token", Map.of()));

        server.createContext("/override", ex -> {
            recorded.add(new RecordedRequest(ex.getRequestMethod(),
                    "authorization", ex.getRequestHeaders().getFirst("Authorization"), null));
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });

        activity.request(jsonUtil.toJson(Map.of(
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/override",
                "credentialId", "c",
                "headers", Map.of("Authorization", "Bearer user-override"))));

        assertThat(recorded.get(0).headerValue).isEqualTo("Bearer user-override");
    }

    /** 테스트가 캡쳐한 한 번의 요청. headerKey는 검증 대상 헤더 이름 (편의용). */
    private record RecordedRequest(String method, String headerKey, String headerValue, String body) {}

    /** Mockito 없이 동작하는 in-memory CredentialResolver — `resolveByName`만 lookup 동작. */
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
}
