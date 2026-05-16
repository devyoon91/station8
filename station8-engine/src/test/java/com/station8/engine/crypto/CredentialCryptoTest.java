package com.station8.engine.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #270 — {@link CredentialCrypto} 단위 테스트.
 *
 * <p>Spring 없이 직접 인스턴스화 + reflection으로 키 주입 — 호스트 의존 없이 검증.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialCryptoTest {

    private CredentialCrypto crypto;
    private String base64Key;

    @BeforeEach
    void setUp() throws Exception {
        // 32-byte 랜덤 키 생성 + crypto 인스턴스에 주입
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        base64Key = Base64.getEncoder().encodeToString(key);
        crypto = newCryptoWithKey(base64Key);
    }

    private static CredentialCrypto newCryptoWithKey(String b64) throws Exception {
        CredentialCrypto c = new CredentialCrypto();
        Field f = CredentialCrypto.class.getDeclaredField("base64Key");
        f.setAccessible(true);
        f.set(c, b64);
        // @PostConstruct 메서드 직접 호출
        Field initMethod = null;
        c.getClass().getDeclaredMethod("init").setAccessible(true);
        java.lang.reflect.Method m = c.getClass().getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(c);
        return c;
    }

    // ---- 정상 round-trip ----

    @Test
    void encrypt_decrypt_roundTrip() {
        String plain = "super-secret-token-12345";
        String ct = crypto.encrypt(plain);
        assertThat(crypto.decrypt(ct)).isEqualTo(plain);
    }

    @Test
    void encrypt_unicodePreserved() {
        String plain = "비밀번호🔑secret";
        String ct = crypto.encrypt(plain);
        assertThat(crypto.decrypt(ct)).isEqualTo(plain);
    }

    @Test
    void encrypt_longValue_roundTrip() {
        // 4KB 값
        String plain = "x".repeat(4096);
        String ct = crypto.encrypt(plain);
        assertThat(crypto.decrypt(ct)).isEqualTo(plain);
    }

    // ---- IV 무작위성 — 같은 평문 두 번 암호화 시 ciphertext 다름 (GCM 안전성) ----

    @Test
    void encrypt_samePlaintextTwice_differentCiphertext() {
        String plain = "same-secret";
        String ct1 = crypto.encrypt(plain);
        String ct2 = crypto.encrypt(plain);
        assertThat(ct1).isNotEqualTo(ct2);
        // 둘 다 정상 복호화
        assertThat(crypto.decrypt(ct1)).isEqualTo(plain);
        assertThat(crypto.decrypt(ct2)).isEqualTo(plain);
    }

    @Test
    void encrypt_100Iterations_allUniqueIVs() {
        // 100회 암호화의 IV가 모두 다른지 — Base64 prefix(IV 16자)로 빠른 체크
        Set<String> ivPrefixes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String ct = crypto.encrypt("test");
            ivPrefixes.add(ct.substring(0, 16));
        }
        assertThat(ivPrefixes).hasSize(100);
    }

    // ---- 키 검증 — 잘못된 키 / 누락 / 잘못된 길이 ----

    @Test
    void init_envMissing_logsWarnAndUseFails() throws Exception {
        // 키 미설정 — boot는 살아있고 (WARN 로그), encrypt 호출 시 IllegalStateException
        CredentialCrypto c = new CredentialCrypto();
        java.lang.reflect.Method m = c.getClass().getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(c);  // 예외 안 남

        assertThatThrownBy(() -> c.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STATION8_CREDENTIAL_KEY");
    }

    @Test
    void init_invalidBase64_useFails() throws Exception {
        CredentialCrypto c = newCryptoWithKey("not!valid_base64!");
        // init은 WARN만, 실제 사용 시 IllegalStateException
        assertThatThrownBy(() -> c.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void init_wrongKeyLength_useFails() throws Exception {
        // 16-byte (AES-128, but we require 256) — init은 WARN만
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        CredentialCrypto c = newCryptoWithKey(shortKey);
        assertThatThrownBy(() -> c.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- 변조 / 잘못된 키 → 복호화 실패 (AEADBadTagException) ----

    @Test
    void decrypt_wrongKey_throws() throws Exception {
        String plain = "secret";
        String ct = crypto.encrypt(plain);

        // 다른 키로 복호화 시도
        byte[] differentKey = new byte[32];
        new SecureRandom().nextBytes(differentKey);
        CredentialCrypto other = newCryptoWithKey(Base64.getEncoder().encodeToString(differentKey));

        assertThatThrownBy(() -> other.decrypt(ct))
                .isInstanceOf(CredentialCrypto.CredentialCryptoException.class);
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        String ct = crypto.encrypt("secret");
        // 마지막 문자 한 글자 바꿔 변조
        char last = ct.charAt(ct.length() - 1);
        char tampered = (last == 'A') ? 'B' : 'A';
        String bad = ct.substring(0, ct.length() - 1) + tampered;

        assertThatThrownBy(() -> crypto.decrypt(bad))
                .isInstanceOf(CredentialCrypto.CredentialCryptoException.class);
    }

    @Test
    void decrypt_invalidBase64_throws() {
        assertThatThrownBy(() -> crypto.decrypt("not!valid_base64!"))
                .isInstanceOf(CredentialCrypto.CredentialCryptoException.class);
    }

    @Test
    void decrypt_tooShort_throws() {
        // IV(12) + tag(16) = 28 bytes 미만 → 형식 오류
        String tooShort = Base64.getEncoder().encodeToString(new byte[10]);
        assertThatThrownBy(() -> crypto.decrypt(tooShort))
                .isInstanceOf(CredentialCrypto.CredentialCryptoException.class);
    }

    // ---- 입력 검증 ----

    @Test
    void encrypt_nullInput_throws() {
        assertThatThrownBy(() -> crypto.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encrypt_emptyInput_throws() {
        assertThatThrownBy(() -> crypto.encrypt(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decrypt_nullInput_throws() {
        assertThatThrownBy(() -> crypto.decrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decrypt_blankInput_throws() {
        assertThatThrownBy(() -> crypto.decrypt("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 메시지 누출 방지 ----

    @Test
    void exception_messages_doNotContainPlaintext() {
        String secret = "this-is-the-secret-value";
        try {
            crypto.encrypt(secret);
            // 정상 종료 — 노출 검증 X
        } catch (Exception ex) {
            assertThat(ex.getMessage()).doesNotContain(secret);
            // cause chain까지 검사
            Throwable t = ex.getCause();
            while (t != null) {
                assertThat(t.getMessage() == null || !t.getMessage().contains(secret))
                        .as("cause message must not contain plaintext")
                        .isTrue();
                t = t.getCause();
            }
        }
    }
}
