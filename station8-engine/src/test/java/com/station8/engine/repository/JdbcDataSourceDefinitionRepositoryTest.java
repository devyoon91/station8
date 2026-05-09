package com.station8.engine.repository;

import com.station8.engine.entity.DataSourceDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcDataSourceDefinitionRepository} CRUD 검증 (#110).
 */
class JdbcDataSourceDefinitionRepositoryTest {

    private static JdbcTemplate jdbcTemplate;
    private JdbcDataSourceDefinitionRepository repository;

    @BeforeAll
    static void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:ds-def-test;MODE=MariaDB;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS U_LINE_DATASOURCE (
                    ID VARCHAR(50),
                    NAME VARCHAR(100) NOT NULL,
                    JDBC_URL VARCHAR(1000) NOT NULL,
                    USERNAME VARCHAR(255),
                    PASSWORD VARCHAR(2000),
                    DRIVER_CLASS VARCHAR(255),
                    DIALECT VARCHAR(50),
                    HIKARI_OPTIONS CLOB,
                    ENABLED_FL VARCHAR(1) DEFAULT 'Y' NOT NULL,
                    USE_FL VARCHAR(1) DEFAULT 'Y' NOT NULL,
                    VIEW_FL VARCHAR(1) DEFAULT 'Y' NOT NULL,
                    DEL_FL VARCHAR(1) DEFAULT 'N' NOT NULL,
                    REG_DT TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    REG_ID VARCHAR(32),
                    EDIT_DT TIMESTAMP,
                    EDIT_ID VARCHAR(32),
                    CONSTRAINT U_LINE_DATASOURCE_PK PRIMARY KEY (ID),
                    CONSTRAINT U_LINE_DATASOURCE_U01 UNIQUE (NAME)
                )""");
    }

    @BeforeEach
    void init() {
        repository = new JdbcDataSourceDefinitionRepository(jdbcTemplate);
        jdbcTemplate.execute("DELETE FROM U_LINE_DATASOURCE");
    }

    @Test
    void insertAndFindByName_roundTripsAllFields() {
        DataSourceDefinition d = sample("source-oracle");
        repository.insert(d);

        DataSourceDefinition got = repository.findByName("source-oracle");
        assertThat(got).isNotNull();
        assertThat(got.id()).isEqualTo(d.id());
        assertThat(got.jdbcUrl()).isEqualTo(d.jdbcUrl());
        assertThat(got.username()).isEqualTo(d.username());
        assertThat(got.password()).isEqualTo(d.password());
        assertThat(got.driverClass()).isEqualTo(d.driverClass());
        assertThat(got.dialect()).isEqualTo(d.dialect());
        assertThat(got.hikariOptions()).isEqualTo(d.hikariOptions());
        assertThat(got.enabledFl()).isEqualTo("Y");
    }

    @Test
    void findById_returnsNullForUnknown() {
        assertThat(repository.findById("nope")).isNull();
        assertThat(repository.findByName("nope")).isNull();
    }

    @Test
    void findAll_excludesSoftDeleted() {
        repository.insert(sample("a"));
        repository.insert(sample("b"));
        DataSourceDefinition c = sample("c");
        repository.insert(c);
        repository.softDelete(c.id());

        List<DataSourceDefinition> all = repository.findAll();
        assertThat(all).extracting(DataSourceDefinition::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void update_withKeepPasswordIfBlank_preservesPasswordWhenBlankInput() {
        DataSourceDefinition orig = sample("preserve-pw");
        repository.insert(orig);

        DataSourceDefinition edit = new DataSourceDefinition(
                orig.id(), orig.name(), "jdbc:h2:mem:NEW_URL", "newuser", "",
                orig.driverClass(), orig.dialect(), orig.hikariOptions(),
                "Y", "Y", "Y", "N", orig.regDt(), orig.regId(),
                LocalDateTime.now(), "admin");
        repository.update(edit, /*keepPasswordIfBlank*/ true);

        DataSourceDefinition got = repository.findByName("preserve-pw");
        assertThat(got.password()).isEqualTo(orig.password()); // 보존
        assertThat(got.jdbcUrl()).isEqualTo("jdbc:h2:mem:NEW_URL");
        assertThat(got.username()).isEqualTo("newuser");
    }

    @Test
    void update_withoutKeepPassword_replacesPassword() {
        DataSourceDefinition orig = sample("replace-pw");
        repository.insert(orig);

        DataSourceDefinition edit = new DataSourceDefinition(
                orig.id(), orig.name(), orig.jdbcUrl(), orig.username(), "newpass",
                orig.driverClass(), orig.dialect(), orig.hikariOptions(),
                "Y", "Y", "Y", "N", orig.regDt(), orig.regId(),
                LocalDateTime.now(), "admin");
        repository.update(edit, /*keepPasswordIfBlank*/ false);

        DataSourceDefinition got = repository.findByName("replace-pw");
        assertThat(got.password()).isEqualTo("newpass");
    }

    @Test
    void setEnabled_togglesFlag() {
        DataSourceDefinition d = sample("toggle");
        repository.insert(d);

        repository.setEnabled(d.id(), false);
        assertThat(repository.findByName("toggle").enabledFl()).isEqualTo("N");

        repository.setEnabled(d.id(), true);
        assertThat(repository.findByName("toggle").enabledFl()).isEqualTo("Y");
    }

    @Test
    void softDelete_alsoDisables() {
        DataSourceDefinition d = sample("delete-me");
        repository.insert(d);

        repository.softDelete(d.id());
        // findByName 은 DEL_FL='N' 만 반환
        assertThat(repository.findByName("delete-me")).isNull();
        // 직접 SQL로 확인 — DEL_FL='Y', ENABLED_FL='N' 모두 적용되었어야 함
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM U_LINE_DATASOURCE WHERE ID = ? AND DEL_FL = 'Y' AND ENABLED_FL = 'N'",
                Integer.class, d.id());
        assertThat(count).isEqualTo(1);
    }

    private static DataSourceDefinition sample(String name) {
        return new DataSourceDefinition(
                UUID.randomUUID().toString(),
                name,
                "jdbc:oracle:thin:@host:1521:ORCL",
                "etl_user",
                "secret",
                "oracle.jdbc.OracleDriver",
                "oracle",
                "{\"maximum-pool-size\":\"5\"}",
                "Y", "Y", "Y", "N",
                LocalDateTime.now(), "admin",
                null, null);
    }
}
