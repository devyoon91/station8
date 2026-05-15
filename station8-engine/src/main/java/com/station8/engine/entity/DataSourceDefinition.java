package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_DATASOURCE 엔티티 — 어드민 UI에서 운영자가 동적으로 등록한 DataSource 정의 (#110).
 *
 * <p>비밀번호는 plain text로 저장된다 (#112 후속에서 시크릿 통합). 환경변수 치환
 * (e.g., {@code ${DB_PASSWORD}})은 가능 — 부팅 시 Spring placeholder가 풀어주지 않으므로
 * 본 record에는 raw 문자열이 그대로 들어온다.</p>
 *
 * <p>application.properties의 {@code station8.datasources.<name>.*} 정적 선언과 이름 충돌 시
 * 정적이 win — DB 행은 ENABLED_FL='N'으로 비활성화되거나 부팅 시 무시된다.</p>
 */
public record DataSourceDefinition(
        String id,
        String name,
        String jdbcUrl,
        String username,
        String password,
        String driverClass,
        String dialect,
        /** Hikari 옵션 raw JSON ({@code {"maximum-pool-size":"10",...}}). null/빈 문자열이면 기본값. */
        String hikariOptions,
        /** 'Y'/'N' — 'N'이면 풀 build 안 함 (운영자가 명시적으로 사용 중지). */
        String enabledFl,
        String delFl,
        LocalDateTime regDt,
        String regId,
        LocalDateTime editDt,
        String editId
) {
}
