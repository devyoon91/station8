package com.station8.engine.datasource;

import com.station8.engine.dialect.DbDialect;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

/**
 * 이름으로 등록된 멀티 DataSource를 조회하는 표준 API.
 *
 * <p>D3(a) 결정 — 액티비티(코어 + 플러그인 모두)가 동일하게 사용:</p>
 * <pre>{@code
 * @Activity("MIGRATE")
 * public String migrate(String input, DataSourceRegistry ds) {
 *     JdbcTemplate src = ds.jdbc("source-oracle");
 *     JdbcTemplate dst = ds.jdbc("target-mart");
 *     // ... R/W
 * }
 * }</pre>
 *
 * <p>{@code primary}는 항상 등록되어 있다 — D2(c) 결정에 따라
 * ``station8.datasources.primary``가 명시되면 그것을, 아니면 ``spring.datasource``의
 * 자동 구성 DataSource를 가리킨다.</p>
 *
 * <p>등록 출처(#110):</p>
 * <ul>
 *   <li>{@code primary} — Spring autoconfig 또는 station8.datasources.primary (절대 unregister 불가)</li>
 *   <li>STATIC — application.properties의 station8.datasources.&lt;name&gt;.* (UI에서 수정 불가)</li>
 *   <li>DYNAMIC — 어드민 UI에서 등록(#110)된 항목, U_LINE_DATASOURCE에 영속화 (UI에서 수정/삭제 가능)</li>
 * </ul>
 */
public interface DataSourceRegistry {

    /** 등록된 DataSource 이름 목록 (정의된 순서). */
    Set<String> names();

    /** 이름으로 raw DataSource 조회. 미등록 이름이면 {@link IllegalArgumentException}. */
    DataSource dataSource(String name);

    /** 이름으로 캐시된 {@link JdbcTemplate} 조회. 호출마다 새로 만들지 않고 풀당 1회만 생성. */
    JdbcTemplate jdbc(String name);

    /** 이름의 SQL 방언. 명시 override 우선, 아니면 URL에서 추론(D6). */
    DbDialect dialect(String name);

    /** {@code SELECT 1}에 준하는 가벼운 ping. 실패 시 메시지가 채워짐. */
    TestResult testConnection(String name);

    /** 어드민 UI / 진단용 — 비밀번호 제외 메타데이터 + 풀 통계 스냅샷. */
    List<DataSourceInfo> snapshot();

    /** 단일 이름의 메타데이터 + 풀 통계 스냅샷. */
    DataSourceInfo info(String name);

    /** 등록 출처(STATIC = properties / DYNAMIC = UI). primary는 항상 PRIMARY. 미등록은 NONE. */
    Source sourceOf(String name);

    /**
     * 동적(UI) 등록 — 풀 빌드 + health check + registry에 추가. 이름 충돌 시 예외.
     * 본 메서드가 던지는 예외는 호출자(서비스/컨트롤러)가 잡아 사용자에게 노출한다.
     *
     * @param spec 등록할 raw 설정
     * @return 등록 직후 health check 결과 — DOWN이어도 등록은 유지(D5)
     */
    TestResult register(DynamicSpec spec);

    /**
     * 동적 항목의 풀을 즉시 graceful-drain하고 새 풀로 교체한다(D2). 정적/primary는 불가.
     */
    TestResult swap(DynamicSpec spec);

    /** 동적 항목 풀 close + registry에서 제거. 정적/primary는 불가. */
    void unregister(String name);

    /**
     * 동적 등록 spec — 어드민 UI 폼 / 부팅 로더가 동일하게 사용.
     *
     * @param name           이름 (정적 또는 primary와 충돌 불가)
     * @param jdbcUrl        JDBC URL
     * @param username       접속 계정
     * @param password       비밀번호 (plain text — D9(a))
     * @param driverClass    드라이버 클래스 (null/blank이면 URL에서 자동 추론)
     * @param dialect        명시 dialect (null/blank이면 URL에서 추론)
     * @param hikariOptions  Hikari 옵션 raw map (key: maximum-pool-size 등)
     */
    record DynamicSpec(
            String name,
            String jdbcUrl,
            String username,
            String password,
            String driverClass,
            String dialect,
            java.util.Map<String, String> hikariOptions
    ) {}

    enum Source { PRIMARY, STATIC, DYNAMIC, NONE }

    /**
     * Test 결과.
     *
     * @param name      대상 DS 이름
     * @param success   ping 성공 여부
     * @param latencyMs ping 왕복 시간 (밀리초). 실패 시에도 측정값을 담는다.
     * @param errorMsg  실패 시 메시지 (성공 시 null)
     */
    record TestResult(String name, boolean success, long latencyMs, String errorMsg) {}
}
