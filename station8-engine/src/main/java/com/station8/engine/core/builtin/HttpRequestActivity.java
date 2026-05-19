package com.station8.engine.core.builtin;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.ActivityParam;
import com.station8.engine.annotation.ActivityParam.Kind;
import com.station8.engine.annotation.LineDefinition;
import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.core.builtin.network.NetworkPolicy;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M18 built-in HTTP request 활동 — {@code @Activity("http.request")}.
 *
 * <p>라인 빌더에서 "외부 API 호출" 노드를 추가하면 본 활동이 호출된다. M16 표현식과 결합해
 * URL/body/headers를 동적으로 구성하고, M17 credential vault와 결합해 Authorization을 자동
 * 주입한다. SSRF 방어는 별도 NetworkPolicy(M18 sub-issue 2)에서 본 활동 호출 직전에 적용.</p>
 *
 * <h3>입력 / 응답</h3>
 * 자세한 shape는 {@link HttpRequestInput} / {@link HttpRequestResult} 참조.
 *
 * <h3>재시도</h3>
 * <ul>
 *   <li>2xx / 3xx → 성공</li>
 *   <li>4xx → {@link NoRetryException} 즉시 final-fail (재시도 무의미)</li>
 *   <li>5xx → 일반 RuntimeException으로 throw, 엔진이 backoff 후 재시도</li>
 *   <li>IOException / timeout → 일반 RuntimeException, 재시도 대상</li>
 *   <li>입력 검증 실패(method 화이트리스트 위반, url 절대경로 위반 등) → {@link NoRetryException}</li>
 * </ul>
 *
 * <h3>credential 자동 헤더 (input.credentialId)</h3>
 * <table>
 *   <tr><th>type</th><th>주입</th></tr>
 *   <tr><td>{@code http_bearer}</td><td>{@code Authorization: Bearer <value>}</td></tr>
 *   <tr><td>{@code http_basic}</td><td>{@code Authorization: Basic base64(schema.username + ":" + value)}</td></tr>
 *   <tr><td>{@code api_key}</td><td>{@code schema.header} 명시 — 예: {@code X-API-Key: <value>}</td></tr>
 *   <tr><td>{@code generic}</td><td>자동 주입 0 — 사용자가 headers에 직접 표현식으로</td></tr>
 * </table>
 *
 * <p>사용자가 input.headers에 같은 키를 지정하면 그 값이 우선 (override 가능).</p>
 *
 * <h3>HttpClient lifecycle</h3>
 * 본 클래스는 thread-safe singleton {@link HttpClient}를 한 번만 만들고 재사용. 각 활동 호출
 * 마다 새 clinet를 만들면 connection pool 빌드/해체 비용으로 무거워지므로 — JDK 기본 HttpClient는
 * 자체 풀을 관리한다.
 */
@Component
@LineDefinition("HttpBuiltin")
public class HttpRequestActivity {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestActivity.class);

    /** HTTP 메서드 화이트리스트 — 그 외는 NoRetryException. */
    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    /** body 무시 메서드 — RFC상 GET/DELETE에 body는 권장 안 됨. */
    private static final Set<String> BODILESS_METHODS = Set.of("GET", "DELETE");

    private final JsonUtil jsonUtil;
    private final CredentialResolver credentialResolver;
    private final NetworkPolicy networkPolicy;
    private final HttpClient httpClient;

    /** 운영 코드 — 모든 의존성 주입. */
    @Autowired
    public HttpRequestActivity(JsonUtil jsonUtil,
                               CredentialResolver credentialResolver,
                               NetworkPolicy networkPolicy) {
        this(jsonUtil, credentialResolver, networkPolicy, defaultHttpClient());
    }

    /** 테스트 — HttpClient를 외부에서 주입. 로컬 fixture server로 교체용. */
    HttpRequestActivity(JsonUtil jsonUtil,
                        CredentialResolver credentialResolver,
                        NetworkPolicy networkPolicy,
                        HttpClient httpClient) {
        this.jsonUtil = jsonUtil;
        this.credentialResolver = credentialResolver;
        this.networkPolicy = networkPolicy;
        this.httpClient = httpClient;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Activity(value = "http.request", retryCount = 3, backoffSeconds = 2,
            description = "HTTP request 노드 — built-in. method/url/headers/body/credentialId 입력으로.",
            params = {
                @ActivityParam(name = "method", kind = Kind.SELECT, required = true,
                        options = {"GET", "POST", "PUT", "DELETE", "PATCH"},
                        defaultValue = "GET",
                        description = "HTTP 메서드. POST/PUT/PATCH만 body 사용."),
                @ActivityParam(name = "url", kind = Kind.STRING, required = true,
                        description = "절대 URL (http:// 또는 https://). 표현식 사용 가능."),
                @ActivityParam(name = "headers", kind = Kind.OBJECT,
                        description = "추가 헤더 JSON object. credential 자동 헤더보다 우선.",
                        defaultValue = "{}"),
                @ActivityParam(name = "body", kind = Kind.OBJECT,
                        description = "POST/PUT/PATCH 본문. string이면 그대로, object면 JSON serialize."),
                @ActivityParam(name = "timeoutMs", kind = Kind.NUMBER,
                        description = "ms 단위. default 30000, max 300000.",
                        defaultValue = "30000"),
                @ActivityParam(name = "credentialId", kind = Kind.CREDENTIAL,
                        description = "vault 등록 이름. type별로 Authorization 등 자동 주입.",
                        options = {"http_bearer", "http_basic", "api_key", "generic"})
            })
    public String request(String inputJson) {
        HttpRequestInput input = parseInput(inputJson);
        validate(input);

        String method = input.method().toUpperCase(Locale.ROOT);
        URI uri = parseUri(input.url());

        // SSRF 방어 (#289) — 호출 직전에 정책 검증. 위반 시 NetworkPolicyViolationException
        // (NoRetryException 상속) → 엔진이 즉시 final-fail.
        networkPolicy.check(uri);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(input.effectiveTimeoutMs()));

        // body 직렬화 — bodiless 메서드는 무시
        byte[] bodyBytes = null;
        String contentTypeFromBody = null;
        if (!BODILESS_METHODS.contains(method) && input.body() != null) {
            bodyBytes = serializeBody(input.body());
            // object/list로 들어오면 자동 JSON 헤더 — 사용자가 명시한 headers엔 안 덮어씀
            if (input.body() instanceof Map || input.body() instanceof java.util.List) {
                contentTypeFromBody = "application/json";
            }
        }
        builder.method(method, bodyBytes == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(bodyBytes));

        // credential 자동 헤더 — 사용자 headers와 충돌 시 사용자 win (override 가능)
        Map<String, String> finalHeaders = new LinkedHashMap<>();
        applyCredentialHeaders(finalHeaders, input.credentialId());
        if (contentTypeFromBody != null && !containsKeyIgnoreCase(finalHeaders, "content-type")) {
            finalHeaders.put("Content-Type", contentTypeFromBody);
        }
        if (input.headers() != null) {
            // 사용자 명시 헤더가 마지막에 덮어씀
            for (Map.Entry<String, String> e : input.headers().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    finalHeaders.put(e.getKey(), e.getValue());
                }
            }
        }
        finalHeaders.forEach(builder::header);

        // 실제 호출 — IOException/timeout은 그대로 propagate (재시도 대상)
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new RuntimeException("HTTP I/O failure: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP call interrupted", ex);
        }

        int status = response.statusCode();
        Map<String, String> respHeaders = flattenHeaders(response.headers().map());
        Object bodyOut = decodeBody(response.body(), respHeaders.get("content-type"));

        if (status >= 400 && status < 500) {
            // 재시도 무의미 — 본문은 errorMessage가 아니라 outputData에 넣을 수 없으니 message에 간략히
            // (단, 평문에 민감 정보 들어갈 수 있어 body 자체는 메시지에 안 박음)
            throw new NoRetryException(
                    "HTTP " + status + " — client error, will not retry (uri=" + safeUri(uri) + ")");
        }
        if (status >= 500) {
            throw new RuntimeException(
                    "HTTP " + status + " — server error, retrying (uri=" + safeUri(uri) + ")");
        }

        HttpRequestResult result = new HttpRequestResult(status, respHeaders, bodyOut);
        try {
            return jsonUtil.toJson(result);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize HttpRequestResult", ex);
        }
    }

    /** input JSON → HttpRequestInput. parse 실패는 NoRetryException(입력 형식 오류는 재시도 무의미). */
    private HttpRequestInput parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new NoRetryException("http.request input is empty");
        }
        try {
            return jsonUtil.fromJson(inputJson, HttpRequestInput.class);
        } catch (Exception ex) {
            throw new NoRetryException("http.request input parse failed: " + ex.getMessage(), ex);
        }
    }

    private void validate(HttpRequestInput input) {
        if (input.method() == null || input.method().isBlank()) {
            throw new NoRetryException("http.request method is required");
        }
        String method = input.method().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new NoRetryException("http.request method not allowed: " + input.method()
                    + " (allowed: " + ALLOWED_METHODS + ")");
        }
        if (input.url() == null || input.url().isBlank()) {
            throw new NoRetryException("http.request url is required");
        }
    }

    private URI parseUri(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new NoRetryException("http.request url is not a valid URI: " + url, ex);
        }
        // 검증 순서: scheme 먼저 (file:///etc/passwd 같이 host 없는 케이스도
        // "잘못된 scheme" 메시지로 더 명확하게)
        if (uri.getScheme() == null) {
            throw new NoRetryException("http.request url must be absolute (scheme://host/...): " + url);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new NoRetryException("http.request url scheme must be http or https: " + scheme);
        }
        if (uri.getHost() == null) {
            throw new NoRetryException("http.request url must have host: " + url);
        }
        return uri;
    }

    /** body를 byte[]로. String은 UTF-8, Object/List는 JSON serialize. */
    private byte[] serializeBody(Object body) {
        if (body instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return jsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new NoRetryException("http.request body serialize failed: " + ex.getMessage(), ex);
        }
    }

    /** credentialId 있으면 vault에서 해소 후 타입별 헤더 주입. */
    private void applyCredentialHeaders(Map<String, String> headers, String credentialId) {
        if (credentialId == null || credentialId.isBlank()) return;
        CredentialResolver.Resolved cred = credentialResolver.resolveByName(credentialId);
        if (cred == null) {
            throw new NoRetryException("http.request credentialId not found: " + credentialId);
        }
        switch (cred.type()) {
            case "http_bearer" -> headers.put("Authorization", "Bearer " + cred.value());
            case "http_basic" -> {
                Object username = cred.schema().get("username");
                if (username == null) {
                    throw new NoRetryException(
                            "http_basic credential '" + credentialId + "' missing schema.username");
                }
                String token = username + ":" + cred.value();
                String b64 = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + b64);
            }
            case "api_key" -> {
                Object header = cred.schema().get("header");
                if (header == null) {
                    throw new NoRetryException(
                            "api_key credential '" + credentialId + "' missing schema.header");
                }
                headers.put(header.toString(), cred.value());
            }
            case "generic" -> {
                // 자동 주입 0 — 사용자가 headers에 직접 표현식으로.
                log.debug("credential '{}' is generic — no auto header injection", credentialId);
            }
            default -> log.warn(
                    "Unknown credential type '{}' for '{}' — no auto header injection",
                    cred.type(), credentialId);
        }
    }

    /** HttpResponse multi-value header를 lowercase key + comma-join 값으로 정규화. */
    private Map<String, String> flattenHeaders(Map<String, java.util.List<String>> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : raw.entrySet()) {
            if (e.getKey() == null) continue;  // HTTP/2 pseudo header (:status 등) skip
            out.put(e.getKey().toLowerCase(Locale.ROOT), String.join(",", e.getValue()));
        }
        return out;
    }

    /** content-type 보고 JSON parse 시도, 실패 시 raw string. */
    private Object decodeBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) return "";
        String raw = new String(body, StandardCharsets.UTF_8);
        if (contentType == null) return raw;
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("application/json") || ct.contains("+json")) {
            try {
                return jsonUtil.fromJson(raw, Object.class);
            } catch (Exception ex) {
                // JSON content-type인데 parse 실패 — raw 그대로
                return raw;
            }
        }
        return raw;
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> map, String key) {
        for (String k : map.keySet()) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    /** error message에 노출할 안전한 URI 형태 — userinfo 마스킹. */
    private static String safeUri(URI uri) {
        if (uri.getUserInfo() == null) return uri.toString();
        return uri.getScheme() + "://***@" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + (uri.getPath() != null ? uri.getPath() : "");
    }
}
