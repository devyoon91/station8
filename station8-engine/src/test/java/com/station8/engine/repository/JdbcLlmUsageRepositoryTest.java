package com.station8.engine.repository;

import com.station8.engine.entity.LlmUsageEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #339 — {@link JdbcLlmUsageRepository} insert/조회 검증. H2 in-memory.
 */
class JdbcLlmUsageRepositoryTest {

    private static JdbcTemplate jdbcTemplate;
    private JdbcLlmUsageRepository repository;

    @BeforeAll
    static void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:llm-usage-test;MODE=MariaDB;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS H_LINE_LLM_USAGE (
                    ID VARCHAR(50),
                    INSTANCE_ID VARCHAR(50),
                    NODE_ID VARCHAR(50),
                    ACTIVITY_NAME VARCHAR(100),
                    PROVIDER VARCHAR(50) NOT NULL,
                    MODEL VARCHAR(100) NOT NULL,
                    INPUT_TOKENS INT DEFAULT 0,
                    OUTPUT_TOKENS INT DEFAULT 0,
                    ESTIMATED_COST_USD DECIMAL(12,6),
                    PROMPT_HASH VARCHAR(64),
                    DEL_FL VARCHAR(1) DEFAULT 'N' NOT NULL,
                    REG_DT TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    REG_ID VARCHAR(32),
                    EDIT_DT TIMESTAMP,
                    EDIT_ID VARCHAR(32),
                    CONSTRAINT H_LINE_LLM_USAGE_PK PRIMARY KEY (ID)
                )""");
    }

    @BeforeEach
    void init() {
        repository = new JdbcLlmUsageRepository(jdbcTemplate);
        jdbcTemplate.execute("DELETE FROM H_LINE_LLM_USAGE");
    }

    private static LlmUsageEntry entry(String instanceId, String model, BigDecimal cost) {
        return new LlmUsageEntry(
                null, instanceId, "node-1", "llm.chat",
                "openai-compatible", model, 100, 50, cost, "abcd1234",
                null, null, "engine", null, null);
    }

    @Test
    void insert_generatesIdAndRoundTrips() {
        String id = repository.insert(entry("inst-1", "gpt-4o", new BigDecimal("0.007500")));
        assertThat(id).isNotBlank();

        List<LlmUsageEntry> rows = repository.findByInstanceId("inst-1");
        assertThat(rows).hasSize(1);
        LlmUsageEntry got = rows.get(0);
        assertThat(got.id()).isEqualTo(id);
        assertThat(got.model()).isEqualTo("gpt-4o");
        assertThat(got.inputTokens()).isEqualTo(100);
        assertThat(got.outputTokens()).isEqualTo(50);
        assertThat(got.estimatedCostUsd()).isEqualByComparingTo("0.0075");
        assertThat(got.promptHash()).isEqualTo("abcd1234");
        assertThat(got.regDt()).isNotNull();
    }

    @Test
    void insert_nullCost_isPersistedAsNull() {
        repository.insert(entry("inst-2", "llama3.1", null));

        List<LlmUsageEntry> rows = repository.findByInstanceId("inst-2");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).estimatedCostUsd()).isNull();
    }

    @Test
    void findByInstanceId_onlyReturnsMatching() {
        repository.insert(entry("inst-a", "gpt-4o", null));
        repository.insert(entry("inst-a", "gpt-4o", null));
        repository.insert(entry("inst-b", "gpt-4o", null));

        assertThat(repository.findByInstanceId("inst-a")).hasSize(2);
        assertThat(repository.findByInstanceId("inst-b")).hasSize(1);
        assertThat(repository.findByInstanceId("inst-none")).isEmpty();
    }
}
