package com.station8.engine.repository;

import com.station8.engine.dialect.MariaDbDialect;
import com.station8.engine.entity.LineStation;
import com.station8.engine.entity.LineTrack;
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
                    ID VARCHAR(50),
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
                    EDIT_ID VARCHAR(32),
                    CONSTRAINT U_LINE_STATION_PK PRIMARY KEY (DEFINITION_ID, ID)
                )""");
        // #364 — U_LINE_TRACK도 composite PK (edgeId가 정의 간 충돌하는 회귀 가드용)
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS U_LINE_TRACK (
                    ID VARCHAR(50),
                    DEFINITION_ID VARCHAR(50) NOT NULL,
                    FROM_NODE_ID VARCHAR(50) NOT NULL,
                    TO_NODE_ID VARCHAR(50) NOT NULL,
                    CONDITION_EXPR VARCHAR(500),
                    DEL_FL VARCHAR(1) DEFAULT 'N' NOT NULL,
                    REG_DT TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    REG_ID VARCHAR(32),
                    EDIT_DT TIMESTAMP,
                    EDIT_ID VARCHAR(32),
                    CONSTRAINT U_LINE_TRACK_PK PRIMARY KEY (DEFINITION_ID, ID),
                    CONSTRAINT U_LINE_TRACK_FK02 FOREIGN KEY (DEFINITION_ID, FROM_NODE_ID) REFERENCES U_LINE_STATION(DEFINITION_ID, ID),
                    CONSTRAINT U_LINE_TRACK_FK03 FOREIGN KEY (DEFINITION_ID, TO_NODE_ID) REFERENCES U_LINE_STATION(DEFINITION_ID, ID),
                    CONSTRAINT U_LINE_TRACK_U01 UNIQUE (DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID)
                )""");
    }

    @BeforeEach
    void init() {
        repository = new JdbcLineDefinitionRepository(jdbcTemplate, new MariaDbDialect());
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
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

        LineStation got = repository.findStationById("def-1", stationId);
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

        LineStation got = repository.findStationById("def-1", stationId);
        assertThat(got).isNotNull();
        assertThat(got.datasourceBindings()).isNull();
    }

    @Test
    void findStationById_returnsNullForUnknown() {
        assertThat(repository.findStationById("def-1", "does-not-exist")).isNull();
    }

    /**
     * #364 — composite PK 핵심 회귀: 서로 다른 두 정의가 같은 nodeId('n-1')를 가져도
     * 충돌 없이 공존하고, findStationById가 definitionId 스코프로 정확히 구분한다.
     * (빌더가 정의마다 n-1,n-2... 를 재발급하는 실제 시나리오.)
     */
    @Test
    void compositePk_sameNodeIdAcrossDefinitions_coexistAndScopeCorrectly() {
        // 두 번째 정의 시드
        jdbcTemplate.update("INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM) VALUES (?, ?)",
                "def-2", "OtherFlow");

        // 두 정의 모두 nodeId 'n-1' 사용 — 예전 단일 PK라면 두 번째 INSERT가 Duplicate PK로 실패했음
        repository.insertNode(new LineStation(
                "n-1", "def-1", "First", "ACT_A", null, null, 0, 0,
                "N", null, "tester", null, null, "NONE"));
        repository.insertNode(new LineStation(
                "n-1", "def-2", "Second", "ACT_B", null, null, 0, 0,
                "N", null, "tester", null, null, "NONE"));

        // 정의별로 정확히 자기 역을 돌려준다
        LineStation s1 = repository.findStationById("def-1", "n-1");
        LineStation s2 = repository.findStationById("def-2", "n-1");
        assertThat(s1).isNotNull();
        assertThat(s2).isNotNull();
        assertThat(s1.activityNm()).isEqualTo("ACT_A");
        assertThat(s2.activityNm()).isEqualTo("ACT_B");
        assertThat(s1.nodeNm()).isEqualTo("First");
        assertThat(s2.nodeNm()).isEqualTo("Second");

        // 두 정의의 노드 목록도 서로 격리
        assertThat(repository.findNodesByDefinition("def-1")).hasSize(1);
        assertThat(repository.findNodesByDefinition("def-2")).hasSize(1);
    }

    /**
     * #364 — 트랙(edge)도 동일 회귀: 빌더는 정의마다 edgeId 'e-1'을 재발급한다.
     * composite PK 이전엔 두 번째 정의의 'e-1' INSERT가 Duplicate PK로 실패했음(docker E2E에서 발견).
     */
    @Test
    void compositePk_sameEdgeIdAcrossDefinitions_coexist() {
        jdbcTemplate.update("INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM) VALUES (?, ?)",
                "def-2", "OtherFlow");
        // 각 정의에 노드 2개(같은 nodeId) + 엣지 1개(같은 edgeId 'e-1')
        for (String def : new String[]{"def-1", "def-2"}) {
            repository.insertNode(new LineStation("n-1", def, "A", "ACT", null, null, 0, 0,
                    "N", null, "tester", null, null, "NONE"));
            repository.insertNode(new LineStation("n-2", def, "B", "ACT", null, null, 0, 0,
                    "N", null, "tester", null, null, "NONE"));
            repository.insertEdge(new LineTrack("e-1", def, "n-1", "n-2", null,
                    "N", null, null, null, null));
        }

        // 두 정의가 같은 edgeId 'e-1'을 충돌 없이 보유, 정의별로 격리 조회
        assertThat(repository.findEdgesByDefinition("def-1")).hasSize(1);
        assertThat(repository.findEdgesByDefinition("def-2")).hasSize(1);
        assertThat(repository.findOutgoingEdges("def-1", "n-1")).singleElement()
                .satisfies(e -> assertThat(e.id()).isEqualTo("e-1"));
        assertThat(repository.findOutgoingEdges("def-2", "n-1")).singleElement()
                .satisfies(e -> assertThat(e.toNodeId()).isEqualTo("n-2"));
    }
}
