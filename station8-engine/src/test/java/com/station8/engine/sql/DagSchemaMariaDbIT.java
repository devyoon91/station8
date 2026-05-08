package com.station8.engine.sql;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MariaDB 컨테이너에 ``schema-mariadb.sql``을 적용하여 스키마 호환성을 검증한다.
 *
 * <p>Docker 데몬이 필요하므로 기본 비활성화 — 활성화 옵션:</p>
 * <ul>
 *   <li>{@code -DrunDockerTests=true} (Gradle: {@code -DrunDockerTests=true})</li>
 *   <li>{@code TESTCONTAINERS_RYUK_DISABLED=true}는 CI 환경에서 권장</li>
 * </ul>
 *
 * <p>로컬 검증:</p>
 * <pre>
 *   ./gradlew :station8-engine:test -DrunDockerTests=true
 * </pre>
 */
@Testcontainers
@EnabledIfSystemProperty(named = "runDockerTests", matches = "true",
        disabledReason = "Docker 데몬 필요. -DrunDockerTests=true로 활성화.")
class DagSchemaMariaDbIT {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11")
            .withDatabaseName("swe_test")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void applySchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setUrl(mariadb.getJdbcUrl());
        ds.setUsername(mariadb.getUsername());
        ds.setPassword(mariadb.getPassword());

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("sql/schema-mariadb.sql"));
        populator.setSeparator(";");
        populator.execute(ds);

        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void mariadbAcceptsSchemaScript() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name IN ('U_LINE_DEFINITION','U_LINE_STATION','U_LINE_TRACK','U_LINE_SCHEDULE')",
                Integer.class);
        assertThat(count).as("DAG/Schedule 테이블 4종이 모두 생성되어야 함").isEqualTo(4);
    }

    @Test
    void canInsertDefinitionWithNodesAndEdges() {
        jdbc.update("""
                INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, DESCRIPTION, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, 'Y', 'Y', 'Y', 'N')
                """, "mdb-def-1", "MariaDbDag", "Testcontainers 검증", 1);

        jdbc.update("""
                INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, INPUT_PARAMS, POS_X_NO, POS_Y_NO, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N')
                """, "mdb-n-a", "mdb-def-1", "Start", "MIGRATION_WRITE", "{}", 0, 0);
        jdbc.update("""
                INSERT INTO U_LINE_STATION (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, INPUT_PARAMS, POS_X_NO, POS_Y_NO, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N')
                """, "mdb-n-b", "mdb-def-1", "End", "MIGRATION_WRITE", "{}", 200, 0);
        jdbc.update("""
                INSERT INTO U_LINE_TRACK (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, 'Y', 'Y', 'N')
                """, "mdb-e-1", "mdb-def-1", "mdb-n-a", "mdb-n-b");

        Integer nodes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM U_LINE_STATION WHERE DEFINITION_ID = ?", Integer.class, "mdb-def-1");
        Integer edges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM U_LINE_TRACK WHERE DEFINITION_ID = ?", Integer.class, "mdb-def-1");
        assertThat(nodes).isEqualTo(2);
        assertThat(edges).isEqualTo(1);
    }

    @Test
    void duplicateDefinitionNameVersionRejected() {
        jdbc.update("""
                INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('mdb-dup-1', 'MdbDup', 1, 'Y', 'Y', 'Y', 'N')
                """);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO U_LINE_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('mdb-dup-2', 'MdbDup', 1, 'Y', 'Y', 'Y', 'N')
                """))
                .as("(DEFINITION_NM, VERSION_NO) unique 제약으로 차단되어야 함")
                .hasMessageContaining("Duplicate");
    }
}
