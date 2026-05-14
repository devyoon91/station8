package com.station8.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;

/**
 * 부팅 시 초기 ADMIN 사용자 시드 (#121, env-binding 결함 수정 #232).
 *
 * <h3>시드 시점 (멱등)</h3>
 * <ol>
 *   <li>같은 username이 이미 존재 → skip</li>
 *   <li>{@code station8.initial-admin.password} 명시 → 그 비밀번호로 시드</li>
 *   <li>비밀번호 미설정 + DB에 사용자가 0명 → <strong>랜덤 비밀번호 자동 생성 + 콘솔에 1회 출력</strong>
 *       (chicken-and-egg 방지). 운영자는 로그에서 비밀번호를 복사해 첫 로그인 후
 *       {@code /me/password}에서 변경 권장.</li>
 *   <li>비밀번호 미설정 + 사용자가 이미 있음 → skip</li>
 * </ol>
 *
 * <p>랜덤 비밀번호는 {@link SecureRandom} + 정책(8자+ 숫자+ 특수)을 만족하는 16자.</p>
 *
 * <h3>운영자 가시성 (#232 / 옵션 C)</h3>
 * 매 부팅마다 INFO 한 줄로 admin 상태를 출력한다 — 비밀번호 출처/존재 여부 즉시 확인 가능:
 * <pre>InitialAdmin: username=admin, status=seeded, source=configured (env or properties)</pre>
 *
 * <h3>env 변수 ↔ property 매핑 (#232)</h3>
 * <ul>
 *   <li>{@code STATION8_INITIAL_ADMIN_USERNAME} ↔ {@code station8.initial-admin.username}</li>
 *   <li>{@code STATION8_INITIAL_ADMIN_PASSWORD} ↔ {@code station8.initial-admin.password}</li>
 * </ul>
 * 이전 경로 {@code station8.security.initial-admin.*}는 env 이름과 어긋나 바인딩 실패 — #232에서 단축.
 */
@Component
public class InitialAdminSeeder {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminSeeder.class);

    /** ambiguous chars(0/O, 1/l/I) 제외 — 콘솔에서 복사 시 혼동 방지. */
    private static final String SAFE_ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final String SAFE_SPECIAL = "!@#$%&*+-=";

    private final LineUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @Value("${station8.initial-admin.username:admin}")
    private String initialAdminUsername;

    @Value("${station8.initial-admin.password:}")
    private String initialAdminPassword;

    public InitialAdminSeeder(LineUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfNeeded() {
        boolean configured = initialAdminPassword != null && !initialAdminPassword.isBlank();

        if (repository.findByUsername(initialAdminUsername) != null) {
            // 이미 존재 — 멱등 skip. env에 비밀번호 줬는데 기존 admin 있으면 운영자 혼동 가능 → WARN
            if (configured) {
                log.warn("Initial admin '{}' already exists in DB — STATION8_INITIAL_ADMIN_PASSWORD" +
                        " is IGNORED. To apply a new password: (1) login + change at /me/password," +
                        " or (2) reset via DB and restart, or (3) 'docker compose down -v' to wipe.",
                        initialAdminUsername);
            }
            // #232 옵션 C — 매 부팅 INFO 한 줄로 운영자 가시성 확보
            logStatus("pre-existing", configured ? "ignored (admin already in DB)" : "n/a");
            return;
        }

        String passwordToUse;
        String source;
        if (configured) {
            String policyError = PasswordPolicy.validate(initialAdminPassword);
            if (policyError != null) {
                log.warn("Configured initial admin password violates policy — seed skipped: {}",
                        policyError);
                logStatus("skip", "configured-but-policy-violation");
                return;
            }
            passwordToUse = initialAdminPassword;
            source = "configured (env STATION8_INITIAL_ADMIN_PASSWORD or properties)";
        } else if (repository.count() == 0) {
            // env 미설정 + DB에 사용자 0명 — 첫 부팅 chicken-and-egg 방지
            passwordToUse = generateSafePassword();
            source = "auto-generated";
        } else {
            logStatus("skip", "no password configured and other users exist");
            return;
        }

        LineUser admin = new LineUser(
                UUID.randomUUID().toString(),
                initialAdminUsername,
                passwordEncoder.encode(passwordToUse),
                "Initial Admin",
                "Y", Set.of("ADMIN", "USER"),
                "Y", "Y", "N", null, "system", null, null);
        repository.insert(admin);

        if ("auto-generated".equals(source)) {
            // 콘솔에 1회만 — DB에는 해시만, raw는 절대 저장 안 함
            log.warn("============================================================");
            log.warn("  Auto-generated initial ADMIN account");
            log.warn("    username: {}", initialAdminUsername);
            log.warn("    password: {}", passwordToUse);
            log.warn("  This password is shown ONCE. Save it now.");
            log.warn("  After login, change it via /me/password or set");
            log.warn("  station8.initial-admin.password (env STATION8_INITIAL_ADMIN_PASSWORD)");
            log.warn("  to skip auto-gen on first boot.");
            log.warn("============================================================");
        } else {
            log.info("Seeded initial ADMIN user: {} (from configured password)",
                    initialAdminUsername);
        }
        logStatus("seeded", source);
    }

    /**
     * #232 옵션 C — 매 부팅 INFO 한 줄로 admin 상태/출처를 출력.
     * 운영자가 컨테이너 로그만으로 "비밀번호가 어디서 왔는지/적용됐는지" 즉시 판단 가능.
     */
    private void logStatus(String status, String source) {
        log.info("InitialAdmin: username={}, status={}, source={}",
                initialAdminUsername, status, source);
    }

    /**
     * 정책 충족 + 모호 문자 제외한 랜덤 비밀번호. 16자 (영숫자 14 + 숫자 1 + 특수 1).
     */
    String generateSafePassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 14; i++) {
            sb.append(SAFE_ALPHABET.charAt(random.nextInt(SAFE_ALPHABET.length())));
        }
        // 정책 보장: 숫자 + 특수문자 한 자씩 끝에 fix
        sb.append(random.nextInt(10));
        sb.append(SAFE_SPECIAL.charAt(random.nextInt(SAFE_SPECIAL.length())));
        return sb.toString();
    }
}
