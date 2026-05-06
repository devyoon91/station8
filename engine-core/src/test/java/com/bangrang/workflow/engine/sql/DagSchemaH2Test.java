package com.bangrang.workflow.engine.sql;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema-h2.sql 전체를 인메모리 H2에 적용하여 DDL 유효성과 신규 DAG 정의 테이블/인덱스 존재를 검증한다.
 */
class DagSchemaH2Test {

    private static JdbcTemplate jdbcTemplate;
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void applySchema() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:dag_schema_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("sql/schema-h2.sql"));
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void allDagDefinitionTablesExist() throws Exception {
        Set<String> required = new HashSet<>(Set.of("U_WF_DEFINITION", "U_WF_NODE", "U_WF_EDGE"));
        Set<String> found = new HashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (required.contains(name)) found.add(name);
                }
            }
        }
        assertEquals(required, found, "DAG 정의 테이블 3개가 모두 존재해야 함");
    }

    @Test
    void canInsertAndQueryDefinitionWithNodesAndEdges() {
        String defId = "def-1";
        String nodeA = "node-a";
        String nodeB = "node-b";
        String edge = "edge-ab";

        jdbcTemplate.update("""
                INSERT INTO U_WF_DEFINITION (ID, DEFINITION_NM, DESCRIPTION, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, 'Y', 'Y', 'Y', 'N')
                """, defId, "TestDag", "사이클 없는 단순 DAG", 1);

        jdbcTemplate.update("""
                INSERT INTO U_WF_NODE (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, INPUT_PARAMS, POS_X_NO, POS_Y_NO, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N')
                """, nodeA, defId, "Start", "MIGRATION_WRITE", "{}", 100, 100);
        jdbcTemplate.update("""
                INSERT INTO U_WF_NODE (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, INPUT_PARAMS, POS_X_NO, POS_Y_NO, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N')
                """, nodeB, defId, "End", "MIGRATION_WRITE", "{}", 300, 100);

        jdbcTemplate.update("""
                INSERT INTO U_WF_EDGE (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, USE_FL, VIEW_FL, DEL_FL)
                VALUES (?, ?, ?, ?, 'Y', 'Y', 'N')
                """, edge, defId, nodeA, nodeB);

        Integer nodeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM U_WF_NODE WHERE DEFINITION_ID = ?", Integer.class, defId);
        Integer edgeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM U_WF_EDGE WHERE DEFINITION_ID = ?", Integer.class, defId);

        assertEquals(2, nodeCount);
        assertEquals(1, edgeCount);
    }

    @Test
    void duplicateNameAndVersionIsRejected() {
        jdbcTemplate.update("""
                INSERT INTO U_WF_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('dup-1', 'DupDag', 1, 'Y', 'Y', 'Y', 'N')
                """);

        boolean threw = false;
        try {
            jdbcTemplate.update("""
                    INSERT INTO U_WF_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                    VALUES ('dup-2', 'DupDag', 1, 'Y', 'Y', 'Y', 'N')
                    """);
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "(DEFINITION_NM, VERSION_NO) 중복은 U01 unique 제약으로 차단되어야 함");
    }

    @Test
    void duplicateEdgeIsRejected() {
        jdbcTemplate.update("""
                INSERT INTO U_WF_DEFINITION (ID, DEFINITION_NM, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('dup-edge-def', 'DupEdgeDag', 1, 'Y', 'Y', 'Y', 'N')
                """);
        jdbcTemplate.update("""
                INSERT INTO U_WF_NODE (ID, DEFINITION_ID, ACTIVITY_NM, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('n-1', 'dup-edge-def', 'A', 'Y', 'Y', 'N')
                """);
        jdbcTemplate.update("""
                INSERT INTO U_WF_NODE (ID, DEFINITION_ID, ACTIVITY_NM, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('n-2', 'dup-edge-def', 'B', 'Y', 'Y', 'N')
                """);
        jdbcTemplate.update("""
                INSERT INTO U_WF_EDGE (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, USE_FL, VIEW_FL, DEL_FL)
                VALUES ('e-1', 'dup-edge-def', 'n-1', 'n-2', 'Y', 'Y', 'N')
                """);

        boolean threw = false;
        try {
            jdbcTemplate.update("""
                    INSERT INTO U_WF_EDGE (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, USE_FL, VIEW_FL, DEL_FL)
                    VALUES ('e-2', 'dup-edge-def', 'n-1', 'n-2', 'Y', 'Y', 'N')
                    """);
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "(FROM_NODE_ID, TO_NODE_ID) 중복은 U01 unique 제약으로 차단되어야 함");
    }
}
