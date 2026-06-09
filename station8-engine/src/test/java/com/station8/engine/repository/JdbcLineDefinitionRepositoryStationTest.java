package com.station8.engine.repository;

import com.station8.engine.dialect.MariaDbDialect;
import com.station8.engine.entity.LineStation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U_LINE_STATION 의 DATASOURCE_BINDINGS 컬럼 (#113) round-trip 검증.
 */
class JdbcLineDefinitionRepositoryStationTest {

    private static JdbcTemplate jdbcTemplate;
    private JdbcLineDefinitionRepository repository;

    @BeforeAll
    static void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:station-bindings-test;MODE=MariaDB;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS U_LINE_DEFINITION (
                    ID VARCHAR(50) PRIMARY KEY,
                    DEFINITION_NM VARCHAR(100) NOT NULL,
                    DESCRIPTION VARCHAR(500),
                    VERSION_NO INT DEFAULT 1 NOT NULL,
                    ACTIVE_FL VARCHAR(1) DEFAULT 'Y' NOT NULL,
                    DEL_FL VARCHAR(1) DEFAULT 'N' NOT NULL,
                    REG_DT TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    REG_ID VARCHAR(32),
                    EDIT_DT TIMESTAMP,
                    EDIT_ID VARCHAR(32)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS U_LINE_STATION (
                    ID VARCHAR(50) PRIMARY KEY,
                    DEFINITION_ID VARCHAR(50) NOT NULL,
                    NODE_NM VARCHAR(100),
                    ACTIVITY_NM VARCHAR(100) NOT NULL,
                    INPUT_PARAMS CLOB,
                    DATASOURCE_BINDINGS CLOB,
                    STREAM_MODE VARCHAR(20) DEFAULT 'NONE' NOT NULL,
                    POS_X_NO INT,
                    POS_Y_NO INT,
                    DEL_FL VARCHAR(1) DEFAULT 'N' NOT NULL,
                    REG_DT TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    REG_ID VARCHAR(32),
                    EDIT_DT TIMESTAMP,
                    EDIT_ID VARCHAR(32)
                )""");
    }

    @BeforeEach
    void init() {
        repository = new JdbcLineDefinitionRepository(jdbcTemplate, new MariaDbDialect());
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
        // 부모 정의 1개 시드
        jdbcTemplate.update("""
                INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM) VALUES (?, ?)
                """, "def-1", "TestFlow");
    }

    @Test
    void insertNode_andFindStationById_roundTripsDatasourceBindings() {
        String stationId = UUID.randomUUID().toString();
        String bindingsJson = "{\"source\":\"oracle-prod\",\"target\":\"mart\"}";
        LineStation node = new LineStation(
                stationId, "def-1", "Migrate", "MIGRATE",
                "{\"limit\":10}",
                bindingsJson,
                10, 20,
                "N", null, "tester", null, null, "FAN_OUT");
        repository.insertNode(node);

        LineStation got = repository.findStationById(stationId);
        assertThat(got).isNotNull();
        assertThat(got.id()).isEqualTo(stationId);
        assertThat(got.activityNm()).isEqualTo("MIGRATE");
        assertThat(got.inputParams()).isEqualTo("{\"limit\":10}");
        assertThat(got.datasourceBindings()).isEqualTo(bindingsJson);
        assertThat(got.posXNo()).isEqualTo(10);
        assertThat(got.posYNo()).isEqualTo(20);
        assertThat(got.streamMode()).isEqualTo("FAN_OUT");  // M22 round-trip
    }

    @Test
    void insertNode_withNullBindings_isPreserved() {
        String stationId = UUID.randomUUID().toString();
        LineStation node = new LineStation(
                stationId, "def-1", "NoBindings", "NOOP",
                null, null, 0, 0,
                "N", null, "tester", null, null, null);
        repository.insertNode(node);

        LineStation got = repository.findStationById(stationId);
        assertThat(got).isNotNull();
        assertThat(got.datasourceBindings()).isNull();
    }

    @Test
    void findStationById_returnsNullForUnknown() {
        assertThat(repository.findStationById("does-not-exist")).isNull();
    }
}
