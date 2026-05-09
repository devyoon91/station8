package com.station8.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * 부팅 시 초기 ADMIN 사용자 시드 (#121).
 *
 * <p>{@code station8.security.initial-admin.username} (default: {@code admin}) +
 * {@code station8.security.initial-admin.password} 환경변수/프로퍼티로 주입.
 * 비밀번호가 비어있으면 시드 안 함 — 명시적 부트스트랩 의도.</p>
 *
 * <p>이미 같은 username이 존재하면 시드 안 함 (멱등). 비밀번호 변경은 어드민 UI 또는
 * 본인 변경 페이지({@code /me/password})에서.</p>
 */
@Component
public class InitialAdminSeeder {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminSeeder.class);

    private final LineUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    @Value("${station8.security.initial-admin.username:admin}")
    private String initialAdminUsername;

    @Value("${station8.security.initial-admin.password:}")
    private String initialAdminPassword;

    public InitialAdminSeeder(LineUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfConfigured() {
        if (initialAdminPassword == null || initialAdminPassword.isBlank()) {
            log.debug("Initial admin seed skipped — station8.security.initial-admin.password not set");
            return;
        }
        if (repository.findByUsername(initialAdminUsername) != null) {
            log.info("Initial admin '{}' already exists — seed skipped (idempotent)",
                    initialAdminUsername);
            return;
        }
        String policyError = PasswordPolicy.validate(initialAdminPassword);
        if (policyError != null) {
            log.warn("Initial admin password violates policy — seed skipped: {}", policyError);
            return;
        }
        LineUser admin = new LineUser(
                UUID.randomUUID().toString(),
                initialAdminUsername,
                passwordEncoder.encode(initialAdminPassword),
                "Initial Admin",
                "Y", Set.of("ADMIN", "USER"),
                "Y", "Y", "N", null, "system", null, null);
        repository.insert(admin);
        log.info("Seeded initial ADMIN user: {}", initialAdminUsername);
    }
}
