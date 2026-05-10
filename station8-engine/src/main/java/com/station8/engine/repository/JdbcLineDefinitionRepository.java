package com.station8.engine.repository;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineStation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class JdbcLineDefinitionRepository implements LineDefinitionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DbDialect dbDialect;

    public JdbcLineDefinitionRepository(JdbcTemplate jdbcTemplate, DbDialect dbDialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbDialect = dbDialect;
    }

    @Override
    @Transactional(readOnly = true)
    public LineDefinition findDefinitionById(String definitionId) {
        String sql = "SELECT * FROM U_LINE_DEFINITION WHERE ID = ?";
        List<LineDefinition> rows = jdbcTemplate.query(sql, new DefinitionMapper(), definitionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public String findDefinitionIdByNodeId(String nodeId) {
        // 소프트 삭제된 노드도 매칭 — 인스턴스가 실행됐던 당시 정의로 역조회해야 하므로.
        List<String> rows = jdbcTemplate.query(
                "SELECT DEFINITION_ID FROM U_LINE_STATION WHERE ID = ?",
                (rs, rowNum) -> rs.getString(1),
                nodeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public LineStation findStationById(String stationId) {
        // findDefinitionIdByNodeId와 동일하게 DEL_FL 조건 무시 — 인스턴스 실행 중 station 메타에 접근.
        List<LineStation> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_STATION WHERE ID = ?",
                new NodeMapper(),
                stationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineDefinition> findAllActiveDefinitions() {
        String sql = """
                SELECT * FROM U_LINE_DEFINITION
                WHERE DEL_FL = 'N' AND ACTIVE_FL = 'Y'
                ORDER BY DEFINITION_NM ASC, VERSION_NO DESC
                """;
        return jdbcTemplate.query(sql, new DefinitionMapper());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineDefinition> findActiveDefinitionsPage(int offset, int limit) {
        String sql = "SELECT * FROM U_LINE_DEFINITION "
                + "WHERE DEL_FL = 'N' AND ACTIVE_FL = 'Y' "
                + "ORDER BY DEFINITION_NM ASC, VERSION_NO DESC "
                + dbDialect.offsetLimit(offset, limit);
        return jdbcTemplate.query(sql, new DefinitionMapper());
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveDefinitions() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM U_LINE_DEFINITION WHERE DEL_FL = 'N' AND ACTIVE_FL = 'Y'",
                Long.class);
        return n == null ? 0L : n;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineStation> findNodesByDefinition(String definitionId) {
        String sql = "SELECT * FROM U_LINE_STATION WHERE DEFINITION_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new NodeMapper(), definitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineTrack> findEdgesByDefinition(String definitionId) {
        String sql = "SELECT * FROM U_LINE_TRACK WHERE DEFINITION_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new EdgeMapper(), definitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineTrack> findIncomingEdges(String toNodeId) {
        String sql = "SELECT * FROM U_LINE_TRACK WHERE TO_NODE_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new EdgeMapper(), toNodeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineTrack> findOutgoingEdges(String fromNodeId) {
        String sql = "SELECT * FROM U_LINE_TRACK WHERE FROM_NODE_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new EdgeMapper(), fromNodeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineStation> findStartNodes(String definitionId) {
        String sql = """
                SELECT n.* FROM U_LINE_STATION n
                WHERE n.DEFINITION_ID = ? AND n.DEL_FL = 'N'
                  AND NOT EXISTS (
                      SELECT 1 FROM U_LINE_TRACK e
                      WHERE e.TO_NODE_ID = n.ID AND e.DEL_FL = 'N'
                  )
                """;
        return jdbcTemplate.query(sql, new NodeMapper(), definitionId);
    }

    @Override
    @Transactional
    public void insertDefinition(LineDefinition definition) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_DEFINITION
                  (ID, DEFINITION_NM, DESCRIPTION, VERSION_NO, ACTIVE_FL,
                   SLA_SECONDS, SLA_ACTION,
                   USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, ?)
                """,
                definition.id(), definition.definitionNm(), definition.description(),
                definition.versionNo(), definition.activeFl() != null ? definition.activeFl() : "Y",
                definition.slaSeconds(), definition.slaAction(),
                definition.regId());
    }

    @Override
    @Transactional
    public void updateDefinitionMeta(String definitionId, String description, String activeFl) {
        jdbcTemplate.update("""
                UPDATE U_LINE_DEFINITION
                SET DESCRIPTION = ?, ACTIVE_FL = COALESCE(?, ACTIVE_FL), EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, description, activeFl, definitionId);
    }

    /** #138 — SLA 메타 업데이트 (replace 시 사용). null 값도 그대로 SET (SLA 비활성화 가능). */
    @Override
    @Transactional
    public void updateDefinitionSla(String definitionId, Long slaSeconds, String slaAction) {
        jdbcTemplate.update("""
                UPDATE U_LINE_DEFINITION
                SET SLA_SECONDS = ?, SLA_ACTION = ?, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, slaSeconds, slaAction, definitionId);
    }

    @Override
    @Transactional
    public void softDeleteDefinition(String definitionId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_DEFINITION
                SET DEL_FL = 'Y', ACTIVE_FL = 'N', EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, definitionId);
    }

    @Override
    @Transactional
    public void insertNode(LineStation node) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_STATION
                  (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, INPUT_PARAMS, DATASOURCE_BINDINGS,
                   POS_X_NO, POS_Y_NO,
                   USE_FL, VIEW_FL, DEL_FL, REG_DT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
                """,
                node.id(), node.definitionId(), node.nodeNm(), node.activityNm(),
                node.inputParams(), node.datasourceBindings(),
                node.posXNo(), node.posYNo());
    }

    @Override
    @Transactional
    public void softDeleteNodesByDefinition(String definitionId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_STATION SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP
                WHERE DEFINITION_ID = ?
                """, definitionId);
    }

    @Override
    @Transactional
    public void insertEdge(LineTrack edge) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_TRACK
                  (ID, DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID, CONDITION_EXPR,
                   USE_FL, VIEW_FL, DEL_FL, REG_DT)
                VALUES (?, ?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
                """,
                edge.id(), edge.definitionId(), edge.fromNodeId(), edge.toNodeId(),
                edge.conditionExpr());
    }

    @Override
    @Transactional
    public void softDeleteEdgesByDefinition(String definitionId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_TRACK SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP
                WHERE DEFINITION_ID = ?
                """, definitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public int findMaxVersionByName(String definitionNm) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(VERSION_NO), 0) FROM U_LINE_DEFINITION WHERE DEFINITION_NM = ? AND DEL_FL = 'N'",
                Integer.class, definitionNm);
        return max != null ? max : 0;
    }

    @Override
    @Transactional(readOnly = true)
    public LineDefinition findActiveDefinitionByName(String workflowName) {
        // #138 — 같은 이름으로 여러 버전이 있을 수 있어 가장 최근 active 버전 1개만 반환
        String sql = "SELECT * FROM U_LINE_DEFINITION "
                + "WHERE DEFINITION_NM = ? AND ACTIVE_FL = 'Y' AND DEL_FL = 'N' "
                + "ORDER BY VERSION_NO DESC " + dbDialect.limit(1);
        List<LineDefinition> rows = jdbcTemplate.query(sql, new DefinitionMapper(), workflowName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static class DefinitionMapper implements RowMapper<LineDefinition> {
        @Override
        public LineDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            long slaSecRaw = rs.getLong("SLA_SECONDS");
            Long slaSeconds = rs.wasNull() ? null : slaSecRaw;
            return new LineDefinition(
                rs.getString("ID"),
                rs.getString("DEFINITION_NM"),
                rs.getString("DESCRIPTION"),
                rs.getInt("VERSION_NO"),
                rs.getString("ACTIVE_FL"),
                slaSeconds,
                rs.getString("SLA_ACTION"),
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT") != null ? rs.getTimestamp("REG_DT").toLocalDateTime() : null,
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }

    private static class NodeMapper implements RowMapper<LineStation> {
        @Override
        public LineStation mapRow(ResultSet rs, int rowNum) throws SQLException {
            int posX = rs.getInt("POS_X_NO");
            Integer posXVal = rs.wasNull() ? null : posX;
            int posY = rs.getInt("POS_Y_NO");
            Integer posYVal = rs.wasNull() ? null : posY;
            return new LineStation(
                rs.getString("ID"),
                rs.getString("DEFINITION_ID"),
                rs.getString("NODE_NM"),
                rs.getString("ACTIVITY_NM"),
                rs.getString("INPUT_PARAMS"),
                rs.getString("DATASOURCE_BINDINGS"),
                posXVal,
                posYVal,
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT") != null ? rs.getTimestamp("REG_DT").toLocalDateTime() : null,
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }

    private static class EdgeMapper implements RowMapper<LineTrack> {
        @Override
        public LineTrack mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LineTrack(
                rs.getString("ID"),
                rs.getString("DEFINITION_ID"),
                rs.getString("FROM_NODE_ID"),
                rs.getString("TO_NODE_ID"),
                rs.getString("CONDITION_EXPR"),
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT") != null ? rs.getTimestamp("REG_DT").toLocalDateTime() : null,
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }
}
