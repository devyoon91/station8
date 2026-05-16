package com.station8.engine.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * M17 (#270) — AES-GCM 256-bit credential crypto.
 *
 * <p>{@code STATION8_CREDENTIAL_KEY} 환경 변수에서 Base64 인코딩된 32-byte 키를 로딩.
 * env 미설정 또는 잘못된 키 길이 시 부팅 실패 (silent fallback X — 평문 저장 위험 차단).</p>
 *
 * <h3>출력 포맷</h3>
 * {@code Base64( IV(12B) || ciphertext || authTag(16B) )}
 *
 * <p>IV는 평가 호출마다 {@link SecureRandom}으로 12-byte 새로 생성. authTag는 GCM이 자동
 * 결합. 같은 평문 두 번 암호화하면 ciphertext는 다름 (IV 차이) — 이게 GCM의 안전성 핵심.</p>
 *
 * <h3>키 생성 예</h3>
 * <pre>
 *   openssl rand -base64 32
 * </pre>
 *
 * <h3>키 rotation</h3>
 * 본 sub-issue 비범위. {@code STATION8_CREDENTIAL_KEY_NEXT} env로 두 키 동시 로딩 후
 * 일괄 재암호화하는 절차는 #273 docs에서 다룬다.
 *
 * <h3>스레드 안전성</h3>
 * {@link Cipher} 인스턴스는 thread-unsafe → 각 호출마다 신규 생성. 키는 immutable이라
 * thread-safe.
 */
@Component
public class CredentialCrypto {

    private static final Logger log = LoggerFactory.getLogger(CredentialCrypto.class);

    private static final String ALGO = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;       // 256-bit
    private static final int IV_BYTES = 12;        // GCM standard
    private static final int TAG_BITS = 128;       // 16-byte auth tag

    /**
     * env-driven 키. {@code STATION8_CREDENTIAL_KEY} = Base64(32 bytes).
     * 미설정 시 빈 문자열 → {@link #init} 에서 명시적 실패.
     */
    @Value("${station8.credential.key:}")
    private String base64Key;

    private SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * 부팅 시 로딩 시도. 키가 없거나 잘못됐으면 WARN만 — Spring 컨텍스트 자체는 살린다
     * (credential vault를 쓰지 않는 환경/테스트에서 boot 차단되지 않게).
     * 실제 encrypt/decrypt 호출 시 명시적 IllegalStateException으로 fail-fast.
     */
    @PostConstruct
    void init() {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("STATION8_CREDENTIAL_KEY 미설정 — credential vault 호출 시 실패함. " +
                    "운영 환경에선 반드시 설정: openssl rand -base64 32");
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            if (decoded.length != KEY_BYTES) {
                log.warn("STATION8_CREDENTIAL_KEY 길이 오류: {}-byte (Base64 of exactly {} bytes 필요)" +
                        " — credential vault 호출 시 실패함", decoded.length, KEY_BYTES);
                return;
            }
            this.secretKey = new SecretKeySpec(decoded, ALGO);
            log.info("CredentialCrypto initialized — AES-GCM 256-bit key loaded ({} chars Base64)",
                    base64Key.length());
        } catch (IllegalArgumentException ex) {
            log.warn("STATION8_CREDENTIAL_KEY는 valid Base64가 아님 — credential vault 호출 시 실패함: {}",
                    ex.getMessage());
        }
    }

    private void requireKeyLoaded() {
        if (secretKey == null) {
            throw new IllegalStateException(
                    "Credential vault key is not configured. " +
                    "Set STATION8_CREDENTIAL_KEY env (Base64 of 32 random bytes — " +
                    "openssl rand -base64 32). 부팅 로그 WARN 메시지 확인.");
        }
    }

    /**
     * 평문을 AES-GCM 암호화 후 Base64 문자열로 반환.
     *
     * @param plaintext null/빈 입력은 IllegalArgumentException — credential value는
     *                  반드시 non-blank여야 한다는 도메인 규칙
     * @return Base64( IV(12B) || ciphertext || authTag(16B) )
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("plaintext must be non-empty");
        }
        requireKeyLoaded();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // IV(12) + ciphertext+tag → 단일 Base64 string
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            // 평문이 메시지에 들어가지 않도록 cause만 — message에 plaintext 누출 방지
            throw new CredentialCryptoException("AES-GCM encryption failed", ex);
        }
    }

    /**
     * Base64 ciphertext를 복호화해 평문 반환.
     *
     * @param ciphertext {@link #encrypt}의 출력 — null/blank이면 IllegalArgumentException
     * @return 평문 (호출부는 이 값을 절대 응답/로그에 노출 금지)
     * @throws CredentialCryptoException 복호화 실패 (잘못된 키 / 변조된 ciphertext / 형식 오류)
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("ciphertext must be non-blank");
        }
        requireKeyLoaded();
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < IV_BYTES + 16) {
                throw new IllegalArgumentException(
                        "ciphertext too short: " + combined.length + " bytes");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (CredentialCryptoException ex) {
            throw ex;
        } catch (Exception ex) {
            // ciphertext가 메시지에 일부라도 들어가면 안 됨
            throw new CredentialCryptoException("AES-GCM decryption failed", ex);
        }
    }

    /** 평가 도중 예외. cause만 보존, message에 평문/ciphertext 일부도 절대 포함 X. */
    public static class CredentialCryptoException extends RuntimeException {
        public CredentialCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
