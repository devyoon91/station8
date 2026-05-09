package com.station8.engine.datasource;

import com.station8.engine.datasource.DataSourceRegistry.Source;
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

    /** 등록 단위 — 모든 메타데이터를 묶어 atomic swap을 가능하게 한다. */
    private record Slot(
            DataSource ds,
            JdbcTemplate jdbc,
            DbDialect dialect,
            String url,
            String username,
            Source source,
            boolean ownsPool,
            boolean healthy,
            String healthError
    ) {}

    /**
     * 모든 등록 슬롯의 진실 공급원. swap 시 이 맵의 단일 put이 atomic 효과를 낸다.
     * Concurrent 액세스 안전을 위해 ConcurrentHashMap.
     */
    private final Map<String, Slot> slots = new java.util.concurrent.ConcurrentHashMap<>();

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
        installSlot("primary", primary, primaryDialect, primaryUrl, primaryUser,
                Source.PRIMARY, /*ownsPool*/ false);

        // secondary들 build (STATIC)
        for (Map.Entry<String, DataSourceEntry> e : props.getDatasources().entrySet()) {
            String name = e.getKey();
            if ("primary".equals(name)) continue;
            DataSourceEntry entry = e.getValue();
            try {
                HikariDataSource hds = buildHikari(name, entry);
                DbDialect dialect = resolveDialect(entry);
                installSlot(name, hds, dialect, entry.getUrl(), entry.getUsername(),
                        Source.STATIC, /*ownsPool*/ true);
                log.info("DataSource registered (STATIC): {} (url={}, dialect={})",
                        name, entry.getUrl(), dialect.getClass().getSimpleName());
            } catch (RuntimeException ex) {
                log.warn("Failed to build STATIC DataSource {}: {} — registration skipped",
                        name, ex.getMessage());
            }
        }
    }

    /**
     * 슬롯 설치 + 부팅 health-check 1회. 기존 슬롯이 있으면 덮어쓴다 (swap에서도 사용).
     */
    private void installSlot(String name, DataSource ds, DbDialect dialect,
                             String url, String username,
                             Source source, boolean ownsPool) {
        boolean healthy;
        String error = null;
        try (Connection c = ds.getConnection()) {
            healthy = c.isValid(BOOT_HEALTHCHECK_TIMEOUT_SECONDS);
            if (!healthy) error = "isValid() returned false";
        } catch (Exception ex) {
            healthy = false;
            error = ex.getMessage();
        }
        if (!healthy) {
            log.warn("DataSource {} health-check FAILED: {}", name, error);
        }
        slots.put(name, new Slot(ds, new JdbcTemplate(ds), dialect, url, username,
                source, ownsPool, healthy, error));
    }

    private Slot requireSlot(String name) {
        Slot s = slots.get(name);
        if (s == null) {
            throw new IllegalArgumentException("Unknown DataSource name: " + name
                    + " (registered: " + slots.keySet() + ")");
        }
        return s;
    }

    @Override
    public Set<String> names() {
        return Collections.unmodifiableSet(slots.keySet());
    }

    @Override
    public DataSource dataSource(String name) { return requireSlot(name).ds(); }

    @Override
    public JdbcTemplate jdbc(String name) { return requireSlot(name).jdbc(); }

    @Override
    public DbDialect dialect(String name) { return requireSlot(name).dialect(); }

    @Override
    public Source sourceOf(String name) {
        Slot s = slots.get(name);
        return s == null ? Source.NONE : s.source();
    }

    @Override
    public TestResult testConnection(String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            return new TestResult(name, false, 0L, "Unknown DataSource name");
        }
        long start = System.nanoTime();
        try (Connection c = slot.ds().getConnection()) {
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
        List<DataSourceInfo> out = new ArrayList<>(slots.size());
        for (String name : slots.keySet()) {
            out.add(info(name));
        }
        return out;
    }

    @Override
    public DataSourceInfo info(String name) {
        Slot slot = requireSlot(name);
        int active = -1, idle = -1, total = -1;
        if (slot.ds() instanceof HikariDataSource hds) {
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
                slot.url(),
                slot.username(),
                slot.dialect().getClass().getSimpleName(),
                slot.healthy(),
                active, idle, total,
                slot.healthError()
        );
    }

    @Override
    public TestResult register(DynamicSpec spec) {
        validateName(spec.name());
        Slot existing = slots.get(spec.name());
        if (existing != null) {
            throw new IllegalStateException("DataSource '" + spec.name() + "' already registered (source=" + existing.source() + ")");
        }
        DataSourceEntry entry = toEntry(spec);
        HikariDataSource hds = buildHikari(spec.name(), entry);
        DbDialect dialect = resolveDialect(entry);
        installSlot(spec.name(), hds, dialect, spec.jdbcUrl(), spec.username(),
                Source.DYNAMIC, /*ownsPool*/ true);
        log.info("DataSource registered (DYNAMIC): {} (url={}, dialect={})",
                spec.name(), spec.jdbcUrl(), dialect.getClass().getSimpleName());
        return testConnection(spec.name());
    }

    @Override
    public TestResult swap(DynamicSpec spec) {
        Slot old = requireSlot(spec.name());
        if (old.source() != Source.DYNAMIC) {
            throw new IllegalStateException("Cannot swap " + old.source() + " DataSource '" + spec.name() + "' — only DYNAMIC entries support runtime swap");
        }
        DataSourceEntry entry = toEntry(spec);
        HikariDataSource newHds = buildHikari(spec.name(), entry);
        DbDialect dialect = resolveDialect(entry);
        // 새 슬롯 install (old를 atomic하게 덮어씀)
        installSlot(spec.name(), newHds, dialect, spec.jdbcUrl(), spec.username(),
                Source.DYNAMIC, /*ownsPool*/ true);
        // 옛 풀 graceful close — Hikari는 in-flight 트랜잭션이 끝날 때까지 대기 후 닫음
        if (old.ds() instanceof HikariDataSource oldHds) {
            try {
                oldHds.close();
                log.info("Closed previous pool for swapped DataSource: {}", spec.name());
            } catch (Exception ex) {
                log.warn("Failed to close previous pool for {}: {}", spec.name(), ex.getMessage());
            }
        }
        return testConnection(spec.name());
    }

    @Override
    public void unregister(String name) {
        Slot slot = requireSlot(name);
        if (slot.source() != Source.DYNAMIC) {
            throw new IllegalStateException("Cannot unregister " + slot.source() + " DataSource '" + name + "' — only DYNAMIC entries can be removed at runtime");
        }
        slots.remove(name);
        if (slot.ds() instanceof HikariDataSource hds) {
            try {
                hds.close();
                log.info("Unregistered + closed DYNAMIC DataSource pool: {}", name);
            } catch (Exception ex) {
                log.warn("Failed to close pool for unregistered {}: {}", name, ex.getMessage());
            }
        }
    }

    @Override
    public void close() {
        for (Map.Entry<String, Slot> e : slots.entrySet()) {
            if (!e.getValue().ownsPool()) continue;
            if (e.getValue().ds() instanceof HikariDataSource hds) {
                try {
                    hds.close();
                    log.info("Closed DataSource pool: {}", e.getKey());
                } catch (Exception ex) {
                    log.warn("Failed to close DataSource pool {}: {}", e.getKey(), ex.getMessage());
                }
            }
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DataSource name is required");
        }
        if ("primary".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("'primary' is reserved — cannot be registered as DYNAMIC");
        }
        // 허용: 영숫자/하이픈/언더스코어. URL 경로/JSON 키로 안전.
        if (!name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("DataSource name must match [A-Za-z][A-Za-z0-9_-]* — got: " + name);
        }
    }

    private static DataSourceEntry toEntry(DynamicSpec spec) {
        DataSourceEntry e = new DataSourceEntry();
        e.setUrl(spec.jdbcUrl());
        e.setUsername(spec.username());
        e.setPassword(spec.password());
        e.setDriverClassName(spec.driverClass());
        e.setDialect(spec.dialect());
        if (spec.hikariOptions() != null) e.setHikari(new LinkedHashMap<>(spec.hikariOptions()));
        return e;
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
