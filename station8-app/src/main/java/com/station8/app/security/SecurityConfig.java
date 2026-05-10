package com.station8.app.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 설정 (#121).
 *
 * <h3>경로 정책</h3>
 * <ul>
 *   <li>{@code /login}, 정적(/css, /js, /favicon.svg, /error) — permitAll</li>
 *   <li>{@code /admin/**} — ADMIN 역할 필요</li>
 *   <li>{@code /me/**} — 인증 필요 (본인 비밀번호 변경 등)</li>
 *   <li>그 외 {@code /line/**}, {@code /api/**} — 본 이슈 1차에선 permitAll. 점진 적용은 후속 이슈.</li>
 * </ul>
 *
 * <h3>CSRF (DS4)</h3>
 * Spring Security 기본 CSRF 활성. multipart POST 폼은 hidden {@code _csrf} input 필요 —
 * Mustache 템플릿에 {@code _csrf} partial로 일괄 처리.
 *
 * <h3>세션 (DS2)</h3>
 * 메모리 (Spring Boot 기본). 외부 store(Redis 등)는 ops 영역.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // #140 — @PreAuthorize 활성 (라인 정의 ACL 적용)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/favicon.svg", "/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/me/**").authenticated()
                        // 점진 적용 — 본 이슈 1차에선 모니터링/실행 경로 permitAll, 후속 이슈에서 강화
                        .anyRequest().permitAll()
                )
                // JSON API는 CSRF 면제 — fetch 호출이 token 헤더 안 붙임. 점진 적용으로 본 이슈 후속에서 토큰 인증 도입
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/line/dashboard", false)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );
        // CSRF는 default 활성 — multipart 포함 모든 form은 _csrf token 필요
        return http.build();
    }
}
