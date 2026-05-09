package com.station8.engine.datasource;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.dialect.MariaDbDialect;
import com.station8.engine.dialect.OracleDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link DataSourceRegistry}의 기본 구현. 부팅 시 ``station8.datasources.<name>.*``로 선언된
 * 항목 각각에 대해 {@link HikariDataSource}를 빌드하고 보관한다.
 *
 * <p>{@code primary}는 외부에서 주입(D2(c) — Spring autoconfig 또는 ``station8.datasources.primary``).
 * 본 클래스는 secondary들(primary가 아닌 모든 이름)만 직접 build한다.</p>
 *
 * <p>shutdown 시 {@link Closeable#close()}로 모든 secondary 풀을 닫는다.
 * primary는 외부(Spring) 라이프사이클이라 본 클래스가 닫지 않는다.</p>
 */
public class DefaultDataSourceRegistry implements DataSourceRegistry, Closeable {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataSourceRegistry.class);

    /** {@code SELECT 1} ping 타임아웃 (초). */
    private static final int PING_TIMEOUT_SECONDS = 3;

    /** 부팅 health-check 타임아웃 (초). */
    private static final int BOOT_HEALTHCHECK_TIMEOUT_SECONDS = 5;

    private final Map<String, DataSource> dataSources = new LinkedHashMap<>();
    private final Map<String, JdbcTemplate> jdbcTemplates = new LinkedHashMap<>();
    private final Map<String, DbDialect> dialects = new LinkedHashMap<>();
    private final Map<String, String> jdbcUrls = new LinkedHashMap<>();
    private final Map<String, String> usernames = new LinkedHashMap<>();
    private final Map<String, Boolean> bootHealthy = new LinkedHashMap<>();
    private final Map<String, String> bootHealthError = new LinkedHashMap<>();

    public DefaultDataSourceRegistry(DataSource primary,
                                     DbDialect primaryDialect,
                                     Station8DataSourcesProperties props,
                                     String primaryJdbcUrlFromEnv,
                                     String primaryUsernameFromEnv) {
        // primary 등록 — 풀 라이프사이클은 외부(Spring)
        DataSourceEntry primaryEntry = props.getDatasources().get("primary");
        String primaryUrl = primaryEntry != null && primaryEntry.getUrl() != null
                ? primaryEntry.getUrl() : primaryJdbcUrlFromEnv;
        String primaryUser = primaryEntry != null && primaryEntry.getUsername() != null
                ? primaryEntry.getUsername() : primaryUsernameFromEnv;
        registerEntry("primary", primary, primaryDialect, primaryUrl, primaryUser, /*ownsPool*/ false);

        // secondary들 build
        for (Map.Entry<String, DataSourceEntry> e : props.getDatasources().entrySet()) {
            String name = e.getKey();
            if ("primary".equals(name)) continue; // primary는 위에서 처리
            DataSourceEntry entry = e.getValue();
            try {
                HikariDataSource hds = buildHikari(name, entry);
                DbDialect dialect = resolveDialect(entry);
                registerEntry(name, hds, dialect, entry.getUrl(), entry.getUsername(), /*ownsPool*/ true);
                log.info("DataSource registered: {} (url={}, dialect={})",
                        name, entry.getUrl(), dialect.getClass().getSimpleName());
            } catch (RuntimeException ex) {
                log.warn("Failed to build DataSource {}: {} — registration skipped",
                        name, ex.getMessage());
            }
        }

        // 부팅 health-check (실패해도 등록은 유지 — D4 요구사항)
        for (String name : dataSources.keySet()) {
            try (Connection c = dataSources.get(name).getConnection()) {
                if (c.isValid(BOOT_HEALTHCHECK_TIMEOUT_SECONDS)) {
                    bootHealthy.put(name, Boolean.TRUE);
                } else {
                    bootHealthy.put(name, Boolean.FALSE);
                    bootHealthError.put(name, "isValid() returned false");
                    log.warn("DataSource {} boot health-check FAILED (isValid=false)", name);
                }
            } catch (Exception ex) {
                bootHealthy.put(name, Boolean.FALSE);
                bootHealthError.put(name, ex.getMessage());
                log.warn("DataSource {} boot health-check FAILED: {}", name, ex.getMessage());
            }
        }
    }

    private void registerEntry(String name, DataSource ds, DbDialect dialect,
                               String url, String username, boolean ownsPool) {
        dataSources.put(name, ds);
        jdbcTemplates.put(name, new JdbcTemplate(ds));
        dialects.put(name, dialect);
        jdbcUrls.put(name, url);
        usernames.put(name, username);
        ownedPools.put(name, ownsPool);
    }

    /** primary 풀은 Spring이 관리, secondary 풀은 본 레지스트리가 닫는다. */
    private final Map<String, Boolean> ownedPools = new LinkedHashMap<>();

    @Override
    public Set<String> names() {
        return Collections.unmodifiableSet(dataSources.keySet());
    }

    @Override
    public DataSource dataSource(String name) {
        DataSource ds = dataSources.get(name);
        if (ds == null) {
            throw new IllegalArgumentException("Unknown DataSource name: " + name
                    + " (registered: " + dataSources.keySet() + ")");
        }
        return ds;
    }

    @Override
    public JdbcTemplate jdbc(String name) {
        JdbcTemplate t = jdbcTemplates.get(name);
        if (t == null) {
            throw new IllegalArgumentException("Unknown DataSource name: " + name
                    + " (registered: " + dataSources.keySet() + ")");
        }
        return t;
    }

    @Override
    public DbDialect dialect(String name) {
        DbDialect d = dialects.get(name);
        if (d == null) {
            throw new IllegalArgumentException("Unknown DataSource name: " + name
                    + " (registered: " + dataSources.keySet() + ")");
        }
        return d;
    }

    @Override
    public TestResult testConnection(String name) {
        DataSource ds = dataSources.get(name);
        if (ds == null) {
            return new TestResult(name, false, 0L, "Unknown DataSource name");
        }
        long start = System.nanoTime();
        try (Connection c = ds.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(PING_TIMEOUT_SECONDS);
                s.execute("SELECT 1");
            }
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            return new TestResult(name, true, latencyMs, null);
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            return new TestResult(name, false, latencyMs, ex.getMessage());
        }
    }

    @Override
    public List<DataSourceInfo> snapshot() {
        List<DataSourceInfo> out = new ArrayList<>(dataSources.size());
        for (String name : dataSources.keySet()) {
            out.add(info(name));
        }
        return out;
    }

    @Override
    public DataSourceInfo info(String name) {
        DataSource ds = dataSources.get(name);
        if (ds == null) {
            throw new IllegalArgumentException("Unknown DataSource name: " + name);
        }
        int active = -1, idle = -1, total = -1;
        if (ds instanceof HikariDataSource hds) {
            HikariPoolMXBean pool = hds.getHikariPoolMXBean();
            if (pool != null) {
                try {
                    active = pool.getActiveConnections();
                    idle = pool.getIdleConnections();
                    total = pool.getTotalConnections();
                } catch (Exception ignore) {
                    // MXBean 일시적 미가용 — -1 유지
                }
            }
        }
        return new DataSourceInfo(
                name,
                jdbcUrls.get(name),
                usernames.get(name),
                dialects.get(name).getClass().getSimpleName(),
                Boolean.TRUE.equals(bootHealthy.get(name)),
                active, idle, total,
                bootHealthError.get(name)
        );
    }

    @Override
    public void close() {
        for (Map.Entry<String, Boolean> e : ownedPools.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) continue;
            DataSource ds = dataSources.get(e.getKey());
            if (ds instanceof HikariDataSource hds) {
                try {
                    hds.close();
                    log.info("Closed DataSource pool: {}", e.getKey());
                } catch (Exception ex) {
                    log.warn("Failed to close DataSource pool {}: {}", e.getKey(), ex.getMessage());
                }
            }
        }
    }

    // ---- helpers ----

    private static HikariDataSource buildHikari(String name, DataSourceEntry entry) {
        if (entry.getUrl() == null || entry.getUrl().isBlank()) {
            throw new IllegalStateException("station8.datasources." + name + ".url is required");
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("station8-" + name);
        cfg.setJdbcUrl(entry.getUrl());
        if (entry.getUsername() != null) cfg.setUsername(entry.getUsername());
        if (entry.getPassword() != null) cfg.setPassword(entry.getPassword());
        if (entry.getDriverClassName() != null && !entry.getDriverClassName().isBlank()) {
            cfg.setDriverClassName(entry.getDriverClassName());
        }
        applyHikariOverrides(cfg, entry.getHikari());
        return new HikariDataSource(cfg);
    }

    /**
     * ``station8.datasources.<name>.hikari.*`` 옵션을 HikariConfig에 적용.
     * 자주 쓰이는 키만 매핑 — 누락 키는 WARN 후 무시.
     */
    private static void applyHikariOverrides(HikariConfig cfg, Map<String, String> hikari) {
        if (hikari == null || hikari.isEmpty()) return;
        for (Map.Entry<String, String> e : hikari.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            try {
                switch (key) {
                    case "maximum-pool-size", "maximumPoolSize" -> cfg.setMaximumPoolSize(Integer.parseInt(value));
                    case "minimum-idle", "minimumIdle" -> cfg.setMinimumIdle(Integer.parseInt(value));
                    case "connection-timeout", "connectionTimeout" -> cfg.setConnectionTimeout(Long.parseLong(value));
                    case "idle-timeout", "idleTimeout" -> cfg.setIdleTimeout(Long.parseLong(value));
                    case "max-lifetime", "maxLifetime" -> cfg.setMaxLifetime(Long.parseLong(value));
                    case "validation-timeout", "validationTimeout" -> cfg.setValidationTimeout(Long.parseLong(value));
                    case "auto-commit", "autoCommit" -> cfg.setAutoCommit(Boolean.parseBoolean(value));
                    case "connection-test-query", "connectionTestQuery" -> cfg.setConnectionTestQuery(value);
                    default -> log.warn("Unknown hikari option ignored: {}", key);
                }
            } catch (RuntimeException ex) {
                log.warn("Invalid hikari.{}={} ({}: {})", key, value, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }

    /**
     * D6 — 명시 dialect 우선, 미명시 시 URL에서 추론. ``oracle`` 포함이면 Oracle, 그 외는 MariaDb.
     */
    static DbDialect resolveDialect(DataSourceEntry entry) {
        String explicit = entry.getDialect();
        if (explicit != null) {
            String lower = explicit.trim().toLowerCase();
            if (lower.equals("oracle")) return new OracleDialect();
            if (lower.equals("mariadb") || lower.equals("mysql") || lower.equals("h2")) return new MariaDbDialect();
        }
        String url = entry.getUrl() == null ? "" : entry.getUrl().toLowerCase();
        if (url.contains("oracle")) return new OracleDialect();
        return new MariaDbDialect();
    }
}
