package com.station8.engine.repository;

import com.station8.engine.entity.Credential;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #270 — {@link JdbcCredentialRepository} CRUD 검증. H2 in-memory.
 */
class JdbcCredentialRepositoryTest {

    private static JdbcTemplate jdbcTemplate;
    private JdbcCredentialRepository repository;

    @BeforeAll
    static void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:credential-test;MODE=MariaDB;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS U_LINE_CREDENTIAL (
                    ID VARCHAR(50),
                    NAME VARCHAR(100) NOT NULL,
                    TYPE VARCHAR(50) NOT NULL,
                    VALUE_ENC CLOB NOT NULL,
                    SCHEMA_JSON CLOB,
                    DEL_FL VARCHAR(1) DEFAULT 'N' NOT NULL,
                    REG_DT TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    REG_ID VARCHAR(32),
                    EDIT_DT TIMESTAMP,
                    EDIT_ID VARCHAR(32),
                    CONSTRAINT U_LINE_CREDENTIAL_PK PRIMARY KEY (ID),
                    CONSTRAINT U_LINE_CREDENTIAL_U01 UNIQUE (NAME)
                )""");
    }

    @BeforeEach
    void init() {
        repository = new JdbcCredentialRepository(jdbcTemplate);
        jdbcTemplate.execute("DELETE FROM U_LINE_CREDENTIAL");
    }

    private static Credential sample(String name, String type) {
        return new Credential(
                UUID.randomUUID().toString(), name, type,
                "Y2lwaGVydGV4dC1iYXNlNjQ=",  // dummy Base64 ciphertext
                "{\"k\":\"v\"}",
                "N",
                null, "test", null, null);
    }

    // ---- insert + find ----

    @Test
    void insertAndFindByName_roundTripsAllFields() {
        Credential c = sample("slack-token", "http_bearer");
        repository.insert(c);

        Credential got = repository.findByName("slack-token");
        assertThat(got).isNotNull();
        assertThat(got.id()).isEqualTo(c.id());
        assertThat(got.type()).isEqualTo("http_bearer");
        assertThat(got.valueEnc()).isEqualTo(c.valueEnc());
        assertThat(got.schemaJson()).isEqualTo(c.schemaJson());
        assertThat(got.delFl()).isEqualTo("N");
    }

    @Test
    void findById_returnsCredential() {
        Credential c = sample("api-key-aws", "api_key");
        repository.insert(c);
        assertThat(repository.findById(c.id())).isNotNull();
    }

    @Test
    void findById_unknown_returnsNull() {
        assertThat(repository.findById("nope")).isNull();
    }

    @Test
    void findByName_unknown_returnsNull() {
        assertThat(repository.findByName("nope")).isNull();
    }

    @Test
    void insert_duplicateName_throws() {
        repository.insert(sample("dup", "generic"));
        assertThatThrownBy(() -> repository.insert(sample("dup", "generic")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---- findAllActive ----

    @Test
    void findAllActive_excludesDeleted() {
        repository.insert(sample("active-1", "generic"));
        repository.insert(sample("active-2", "generic"));

        Credential deleted = sample("deleted", "generic");
        repository.insert(deleted);
        repository.softDelete(deleted.id(), "test");

        List<Credential> all = repository.findAllActive();
        assertThat(all).extracting(Credential::name).containsExactly("active-1", "active-2");
    }

    // ---- update ----

    @Test
    void update_changesFieldsExceptId() {
        Credential c = sample("rotate-me", "http_bearer");
        repository.insert(c);

        Credential updated = new Credential(
                c.id(), c.name(), "api_key", "bmV3LWNpcGhlcnRleHQ=", "{\"new\":true}",
                c.delFl(),
                null, c.regId(), null, "rotater");
        repository.update(updated);

        Credential got = repository.findById(c.id());
        assertThat(got.type()).isEqualTo("api_key");
        assertThat(got.valueEnc()).isEqualTo("bmV3LWNpcGhlcnRleHQ=");
        assertThat(got.schemaJson()).isEqualTo("{\"new\":true}");
        assertThat(got.editId()).isEqualTo("rotater");
        assertThat(got.editDt()).isNotNull();
    }

    @Test
    void update_doesNotAffectDeletedRow() {
        Credential c = sample("ghost", "generic");
        repository.insert(c);
        repository.softDelete(c.id(), "test");

        Credential after = new Credential(
                c.id(), c.name(), "api_key", "bmV3", null,
                "N",
                null, c.regId(), null, "x");
        repository.update(after);

        // findById excludes DEL_FL='Y'
        assertThat(repository.findById(c.id())).isNull();
    }

    // ---- soft delete ----

    @Test
    void softDelete_marksAsDeletedAndExcludesFromFinds() {
        Credential c = sample("remove-me", "generic");
        repository.insert(c);

        repository.softDelete(c.id(), "test");

        assertThat(repository.findById(c.id())).isNull();
        assertThat(repository.findByName("remove-me")).isNull();
    }
}
