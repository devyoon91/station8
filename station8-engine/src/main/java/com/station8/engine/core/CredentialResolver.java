package com.station8.engine.core;

import com.station8.engine.crypto.CredentialCrypto;
import com.station8.engine.entity.Credential;
import com.station8.engine.repository.CredentialRepository;
import com.station8.engine.util.JsonUtil;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M17 (#272) — credential vault에서 사용자 표현식의 {@code $credentials} 바인딩을 lazy 해소.
 *
 * <h3>접근 형태</h3>
 * <pre>{@code
 *   {{ $credentials.slack.value }}      → 복호화된 평문 (string)
 *   {{ $credentials.slack.type }}       → "http_bearer"
 *   {{ $credentials.basic.username }}   → schemaJson의 username 필드 (http_basic 타입)
 *   {{ $credentials.basic.value }}      → 복호화된 password
 * }</pre>
 *
 * <h3>Lazy 정책</h3>
 * Top-level {@code $credentials} ProxyObject는 활동마다 1번 생성 ({@link LineContextBindings#from})
 * 되지만 실제 lookup은 사용자 표현식이 {@code .name}으로 액세스할 때만 발생. 즉:
 *
 * <ul>
 *   <li>표현식에서 {@code $credentials} 참조 안 함 → DB 쿼리 0, decrypt 0</li>
 *   <li>{@code $credentials.foo.value} 평가 → findByName 1회 + decrypt 1회</li>
 *   <li>{@code $credentials.foo.username} 평가 (http_basic) → findByName 1회, decrypt 안 함 (schemaJson만)</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>평문은 표현식 평가 동안 ProxyObject 내부 호출 스택에 잠시 존재. JVM GC가 string pool/heap에서
 *       정리하면 회수 (Java String은 명시적 wipe 불가 — 구조적 한계)</li>
 *   <li>ProxyObject는 keys/value 접근만 노출. {@code .getClass()} 같은 Java reflection은 차단
 *       ({@link ExpressionEvaluator}의 {@code HostAccess.NONE} 정책과 결합)</li>
 *   <li>decrypt 실패 시 {@link com.station8.engine.crypto.CredentialCrypto.CredentialCryptoException}을
 *       그대로 위로 전파 → 호출부({@link InputParamsEvaluator})가 활동 FAILED로 격하. 메시지에 평문 누출 없음.</li>
 * </ul>
 *
 * <h3>ACL (비범위)</h3>
 * 본 sub-issue (#272)는 노드 단위 credential 가시성 제한을 포함하지 않음 — 모든 활성 credential을
 * 모든 활동에 노출. 추후 별도 sub-issue로 라인/노드별 ACL 도입.
 */
@Component
public class CredentialResolver {

    private static final String FIELD_VALUE = "value";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_NAME = "name";

    private final CredentialRepository repository;
    private final CredentialCrypto crypto;
    private final JsonUtil jsonUtil;

    public CredentialResolver(CredentialRepository repository,
                              CredentialCrypto crypto,
                              JsonUtil jsonUtil) {
        this.repository = repository;
        this.crypto = crypto;
        this.jsonUtil = jsonUtil;
    }

    /**
     * 사용자 표현식의 {@code $credentials} 바인딩으로 노출할 top-level ProxyObject 반환.
     *
     * <p>Lazy — 본 메서드 자체는 DB 쿼리 안 함. 표현식이 {@code .name}으로 멤버를 액세스할 때
     * {@link #lookupByName} 가 호출되어 1번의 findByName + (value 접근 시) decrypt 수행.</p>
     */
    public Object topLevelBinding() {
        return new TopLevel();
    }

    private Object lookupByName(String name) {
        Credential c = repository.findByName(name);
        return c == null ? null : new SingleCredential(c);
    }

    /**
     * Java 호출자용 — 이름으로 credential을 찾아 평문/메타까지 해소해서 반환.
     *
     * <p>JS 표현식 경로는 {@link #topLevelBinding()}로 ProxyObject를 받지만, built-in 활동
     * 같은 Java 코드는 ProxyObject reflection이 막혀있어 못 쓴다. 본 메서드가 그 자리를 채운다.</p>
     *
     * <p>호출 즉시 decrypt를 수행한다 — lazy 경로(JS proxy)와 다르게 Java 호출은 보통 평문을
     * 바로 쓰므로 게으른 패턴이 의미가 없다. 호출부는 반환된 {@link Resolved#value()}를 절대
     * 응답/로그에 노출하지 말 것.</p>
     *
     * @param name 등록된 credential 이름 (대소문자 정확히)
     * @return 못 찾으면 null. 찾으면 모든 필드(평문 포함) 채워진 record
     */
    public Resolved resolveByName(String name) {
        Credential c = repository.findByName(name);
        if (c == null) return null;
        String value = crypto.decrypt(c.valueEnc());
        Map<String, Object> schema = parseSchemaSafely(c.schemaJson());
        return new Resolved(c.name(), c.type(), value, schema);
    }

    /** schemaJson을 Map으로 안전 파싱 — null/blank/오류 모두 빈 Map. */
    private Map<String, Object> parseSchemaSafely(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = jsonUtil.fromJson(schemaJson, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /**
     * Java 호출자에게 노출하는 해소된 credential.
     *
     * @param name   등록 이름
     * @param type   타입 (http_basic / http_bearer / api_key / generic)
     * @param value  복호화된 평문 — 응답/로그 노출 금지
     * @param schema schemaJson을 Map으로 파싱한 결과 (없으면 빈 Map)
     */
    public record Resolved(String name, String type, String value, Map<String, Object> schema) {}

    /** 등록된 credential 전체 이름 (active만). 표현식이 {@code Object.keys($credentials)} 같은 enumeration할 때 사용. */
    private List<String> activeNames() {
        return repository.findAllActive().stream().map(Credential::name).toList();
    }

    /** Top-level $credentials proxy — name → SingleCredential 또는 null. */
    private class TopLevel implements ProxyObject {
        @Override
        public Object getMember(String key) {
            return lookupByName(key);
        }

        @Override
        public boolean hasMember(String key) {
            return repository.findByName(key) != null;
        }

        @Override
        public Object getMemberKeys() {
            return ProxyArray.fromList(new ArrayList<>(activeNames()));
        }

        @Override
        public void putMember(String key, org.graalvm.polyglot.Value value) {
            throw new UnsupportedOperationException("$credentials is read-only");
        }
    }

    /**
     * 단일 credential proxy — {@code value} / {@code type} / {@code name} + schemaJson 필드.
     *
     * <p>decrypt는 {@code value} 액세스 시점에만 실행 — schema 필드만 쓰면 decrypt 비용 0.</p>
     */
    private class SingleCredential implements ProxyObject {
        private final Credential c;
        private Map<String, Object> schemaCache;  // lazy parse

        SingleCredential(Credential c) {
            this.c = c;
        }

        private Map<String, Object> schema() {
            if (schemaCache != null) return schemaCache;
            if (c.schemaJson() == null || c.schemaJson().isBlank()) {
                schemaCache = Map.of();
                return schemaCache;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = jsonUtil.fromJson(c.schemaJson(), Map.class);
                schemaCache = parsed != null ? parsed : Map.of();
            } catch (Exception ignored) {
                schemaCache = Map.of();
            }
            return schemaCache;
        }

        @Override
        public Object getMember(String key) {
            if (FIELD_VALUE.equals(key)) return crypto.decrypt(c.valueEnc());
            if (FIELD_TYPE.equals(key)) return c.type();
            if (FIELD_NAME.equals(key)) return c.name();
            // 우선순위: 명시 필드 → schemaJson — schemaJson에 value/type/name 같은 키가 있어도 덮어쓰지 않음
            return schema().get(key);
        }

        @Override
        public boolean hasMember(String key) {
            return FIELD_VALUE.equals(key) || FIELD_TYPE.equals(key) || FIELD_NAME.equals(key)
                    || schema().containsKey(key);
        }

        @Override
        public Object getMemberKeys() {
            Set<String> keys = new java.util.LinkedHashSet<>();
            keys.add(FIELD_VALUE);
            keys.add(FIELD_TYPE);
            keys.add(FIELD_NAME);
            keys.addAll(schema().keySet());
            return ProxyArray.fromList(new ArrayList<>(keys));
        }

        @Override
        public void putMember(String key, org.graalvm.polyglot.Value value) {
            throw new UnsupportedOperationException("$credentials.<name> is read-only");
        }
    }
}
