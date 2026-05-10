package com.station8.app.security;

import com.station8.app.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #121 InitialAdminSeeder — env 비었을 때 chicken-and-egg 방지 자동 생성 검증.
 */
@SpringBootTest(classes = Application.class)
class InitialAdminSeederTest {

    @Autowired LineUserRepository repository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired InitialAdminSeeder seeder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetUsers() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());
        jdbcTemplate.execute("DELETE FROM U_LINE_USER_ROLE");
        jdbcTemplate.execute("DELETE FROM U_LINE_USER");
    }

    @Test
    void emptyDbAndNoEnv_seedsAdminWithGeneratedPassword() {
        // env 비움
        ReflectionTestUtils.setField(seeder, "initialAdminPassword", "");
        ReflectionTestUtils.setField(seeder, "initialAdminUsername", "admin");

        seeder.seedIfNeeded();

        LineUser admin = repository.findByUsername("admin");
        assertThat(admin).isNotNull();
        assertThat(admin.roles()).contains("ADMIN");
        // 해시는 raw가 아니어야
        assertThat(admin.passwordHash()).isNotEqualTo("");
        assertThat(admin.passwordHash().length()).isGreaterThan(40);
    }

    @Test
    void existingUsersAndNoEnv_skipsSeed() {
        // 사용자가 이미 있음 (다른 username)
        repository.insert(new LineUser(UUID.randomUUID().toString(),
                "alice", passwordEncoder.encode("Hello!1234"), "Alice",
                "Y", Set.of("USER"), "Y", "Y", "N", null, "test", null, null));

        ReflectionTestUtils.setField(seeder, "initialAdminPassword", "");
        ReflectionTestUtils.setField(seeder, "initialAdminUsername", "admin");

        seeder.seedIfNeeded();

        // admin 이름으로는 시드 안 됨 — 자동 생성은 DB가 비어있을 때만
        assertThat(repository.findByUsername("admin")).isNull();
    }

    @Test
    void configuredPassword_seedsWithThatPassword() {
        ReflectionTestUtils.setField(seeder, "initialAdminPassword", "Hello!1234");
        ReflectionTestUtils.setField(seeder, "initialAdminUsername", "admin");

        seeder.seedIfNeeded();

        LineUser admin = repository.findByUsername("admin");
        assertThat(admin).isNotNull();
        assertThat(passwordEncoder.matches("Hello!1234", admin.passwordHash())).isTrue();
    }

    @Test
    void weakConfiguredPassword_skipsSeed() {
        ReflectionTestUtils.setField(seeder, "initialAdminPassword", "weak");
        ReflectionTestUtils.setField(seeder, "initialAdminUsername", "admin");

        seeder.seedIfNeeded();

        // 정책 위반 → 시드 X
        assertThat(repository.findByUsername("admin")).isNull();
    }

    @Test
    void idempotent_secondCallDoesNothing() {
        ReflectionTestUtils.setField(seeder, "initialAdminPassword", "");
        ReflectionTestUtils.setField(seeder, "initialAdminUsername", "admin");

        seeder.seedIfNeeded();
        LineUser first = repository.findByUsername("admin");
        assertThat(first).isNotNull();
        String firstHash = first.passwordHash();

        // 두 번째 호출 — 같은 사용자 존재라 skip, 비밀번호 그대로
        seeder.seedIfNeeded();
        LineUser second = repository.findByUsername("admin");
        assertThat(second.passwordHash()).isEqualTo(firstHash);
    }

    @Test
    void generatedPassword_satisfiesPolicy() {
        String pw = seeder.generateSafePassword();
        assertThat(PasswordPolicy.validate(pw))
                .as("자동 생성 비밀번호는 정책 통과")
                .isNull();
        assertThat(pw.length()).isEqualTo(16);
    }
}
