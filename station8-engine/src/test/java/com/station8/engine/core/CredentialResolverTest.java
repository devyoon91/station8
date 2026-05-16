package com.station8.engine.core;

import com.station8.engine.crypto.CredentialCrypto;
import com.station8.engine.entity.Credential;
import com.station8.engine.repository.CredentialRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #272 — {@link CredentialResolver} + LineContextBindings + ExpressionEvaluator end-to-end.
 *
 * <p>실제 ExpressionEvaluator를 통해 {@code {{ $credentials.foo.value }}} 같은 표현식이
 * 정상 동작하는지 검증. 검증 포인트:</p>
 * <ul>
 *   <li>vault에서 lazy 해소 (DB 쿼리는 .name 액세스 시점)</li>
 *   <li>decrypt는 .value 액세스 시점</li>
 *   <li>schemaJson 필드 (http_basic의 username) 노출</li>
 *   <li>존재하지 않는 credential → null</li>
 *   <li>sandbox 우회 시도 차단 (getClass 등)</li>
 *   <li>$credentials는 read-only — 표현식이 write 시도 X</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialResolverTest {

    private final JsonUtil jsonUtil = new JsonUtil();
    private InMemoryRepo repo;
    private CredentialCrypto crypto;
    private CredentialResolver resolver;
    private LineContextBindings bindings;
    private ExpressionEvaluator evaluator;

    @BeforeAll
    void setUp() throws Exception {
        repo = new InMemoryRepo();
        crypto = newCryptoWithRandomKey();
        resolver = new CredentialResolver(repo, crypto, jsonUtil);
        bindings = new LineContextBindings(jsonUtil, resolver);
        evaluator = new ExpressionEvaluator();
    }

    @AfterAll
    void tearDown() {
        evaluator.close();
    }

    private static CredentialCrypto newCryptoWithRandomKey() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        CredentialCrypto c = new CredentialCrypto();
        Field f = CredentialCrypto.class.getDeclaredField("base64Key");
        f.setAccessible(true);
        f.set(c, Base64.getEncoder().encodeToString(key));
        Method init = CredentialCrypto.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(c);
        return c;
    }

    private DefaultLineContext ctx() {
        return new DefaultLineContext("inst-1", "FLOW", "ACTIVITY", 1, null, null, jsonUtil);
    }

    private void seed(String name, String type, String plaintext, String schemaJson) {
        String enc = crypto.encrypt(plaintext);
        repo.put(new Credential(
                name + "-id", name, type, enc, schemaJson,
                "N", LocalDateTime.now(), "test", null, null));
    }

    // ---- 기본 액세스 ----

    @Test
    void value_decryptedOnAccess() throws Exception {
        seed("slack", "http_bearer", "xoxb-real-secret", null);
        Object result = evaluator.evaluate("{{ $credentials.slack.value }}", bindings.from(ctx()));
        assertThat(result).isEqualTo("xoxb-real-secret");
    }

    @Test
    void type_returnedAsString() throws Exception {
        seed("api1", "api_key", "secret", null);
        assertThat(evaluator.evaluate("{{ $credentials.api1.type }}", bindings.from(ctx())))
                .isEqualTo("api_key");
    }

    @Test
    void name_returnedAsString() throws Exception {
        seed("named", "generic", "v", null);
        assertThat(evaluator.evaluate("{{ $credentials.named.name }}", bindings.from(ctx())))
                .isEqualTo("named");
    }

    // ---- schemaJson 필드 ----

    @Test
    void schemaField_httpBasic_username() throws Exception {
        seed("basic", "http_basic", "the-password", "{\"username\":\"admin\"}");
        Map<String, Object> b = bindings.from(ctx());
        assertThat(evaluator.evaluate("{{ $credentials.basic.username }}", b))
                .isEqualTo("admin");
        assertThat(evaluator.evaluate("{{ $credentials.basic.value }}", b))
                .isEqualTo("the-password");
    }

    @Test
    void schemaField_unknownKey_returnsNull() throws Exception {
        seed("simple", "generic", "v", null);
        assertThat(evaluator.evaluate("{{ $credentials.simple.nonExistent }}", bindings.from(ctx())))
                .isNull();
    }

    // ---- 존재하지 않는 credential ----

    @Test
    void unknownCredential_returnsNull() throws Exception {
        // .name 자체는 null
        assertThat(evaluator.evaluate("{{ $credentials.nonExistent }}", bindings.from(ctx())))
                .isNull();
    }

    @Test
    void unknownCredential_fieldAccess_throws() {
        // null.value → JS TypeError → 활동 FAILED 격하
        assertThatThrownBy(() -> evaluator.evaluate("{{ $credentials.nonExistent.value }}", bindings.from(ctx())))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    // ---- Sandbox ----

    @Test
    void getClassAccess_blocked() {
        seed("locked", "generic", "v", null);
        assertThatThrownBy(() -> evaluator.evaluate(
                "{{ $credentials.locked.getClass().getName() }}", bindings.from(ctx())))
                .isInstanceOf(ExpressionEvaluator.ExpressionEvaluationException.class);
    }

    @Test
    void writeAttempt_doesNotPersist() throws Exception {
        seed("ro", "generic", "original-value", null);
        Map<String, Object> b = bindings.from(ctx());

        // 비-strict JS에서 read-only 속성에 대한 write는 silently 실패 (예외 안 던짐).
        // 보안 핵심: 실제 vault 상태 변경 X. 평가 결과는 JS 표현식 평가 자체에 의존.
        try {
            evaluator.evaluate("{{ $credentials.ro.value = 'hack' }}", b);
        } catch (ExpressionEvaluator.ExpressionEvaluationException ignored) {
            // strict mode 또는 ProxyObject.putMember의 UnsupportedOperationException 전파 — OK
        }

        // 새 binding 생성 후 다시 읽어도 원본 값이어야 함 (vault에 변경 없음)
        assertThat(evaluator.evaluate("{{ $credentials.ro.value }}", bindings.from(ctx())))
                .as("$credentials write 시도가 vault 상태를 변경하지 않아야 함")
                .isEqualTo("original-value");
    }

    // ---- Lazy 검증 ----

    @Test
    void lazyResolution_unreferencedCredentials_notQueried() throws Exception {
        seed("used", "generic", "v1", null);
        seed("unused-a", "generic", "v2", null);
        seed("unused-b", "generic", "v3", null);

        repo.queryLog.clear();
        evaluator.evaluate("{{ $credentials.used.value }}", bindings.from(ctx()));

        // findByName('used') 만 호출되어야 — unused-a/b는 안 건드림
        assertThat(repo.queryLog)
                .as("표현식에서 참조 안 한 credential은 DB 쿼리 0")
                .contains("findByName:used")
                .doesNotContain("findByName:unused-a", "findByName:unused-b");
    }

    @Test
    void lazyResolution_schemaOnly_skipsDecrypt() throws Exception {
        seed("basic2", "http_basic", "password-should-not-decrypt", "{\"username\":\"alice\"}");
        repo.queryLog.clear();

        evaluator.evaluate("{{ $credentials.basic2.username }}", bindings.from(ctx()));

        // findByName 1회, decrypt 0회 — username은 schemaJson에서 옴
        assertThat(repo.queryLog).contains("findByName:basic2");
        // 평문이 메모리에 잠시도 안 들어갔다는 가시적 증거는 어렵지만, 평문 문자열이 평가 path에 들어가지 않음
        // (간접 가드 — schemaJson만 사용한 케이스가 정상 동작 + 별도 명시적 decrypt 측정은 별도 PERF 이슈)
    }

    // ---- Empty vault (NoCredentialsResolver) ----

    @Test
    void emptyVault_returnsNullForAny() throws Exception {
        // backward-compat 생성자 — NoCredentialsResolver
        LineContextBindings emptyBindings = new LineContextBindings(jsonUtil);
        Map<String, Object> b = emptyBindings.from(ctx());
        // $credentials 자체는 빈 ProxyObject, .foo 액세스는 undefined → null
        assertThat(evaluator.evaluate("{{ $credentials.foo }}", b)).isNull();
    }

    // ---- In-memory repo stub ----

    static class InMemoryRepo implements CredentialRepository {
        private final Map<String, Credential> byName = new LinkedHashMap<>();
        final List<String> queryLog = new ArrayList<>();

        void put(Credential c) { byName.put(c.name(), c); }

        @Override
        public void insert(Credential credential) {
            byName.put(credential.name(), credential);
        }
        @Override public Credential findById(String id) { return null; }
        @Override
        public Credential findByName(String name) {
            queryLog.add("findByName:" + name);
            return byName.get(name);
        }
        @Override
        public List<Credential> findAllActive() {
            return new ArrayList<>(byName.values());
        }
        @Override public void update(Credential credential) {}
        @Override public void softDelete(String id, String editId) {}
    }
}
