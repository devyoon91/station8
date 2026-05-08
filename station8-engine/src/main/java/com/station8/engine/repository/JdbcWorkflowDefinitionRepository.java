package com.station8.engine.repository;

import com.station8.engine.entity.WorkflowDefinition;
import com.station8.engine.entity.WorkflowEdge;
import com.station8.engine.entity.WorkflowNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class JdbcWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkflowDefinitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowDefinition findDefinitionById(String definitionId) {
        String sql = "SELECT * FROM U_WF_DEFINITION WHERE ID = ?";
        List<WorkflowDefinition> rows = jdbcTemplate.query(sql, new DefinitionMapper(), definitionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowNode> findNodesByDefinition(String definitionId) {
        String sql = "SELECT * FROM U_WF_NODE WHERE DEFINITION_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new NodeMapper(), definitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowEdge> findEdgesByDefinition(String definitionId) {
        String sql = "SELECT * FROM U_WF_EDGE WHERE DEFINITION_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new EdgeMapper(), definitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowEdge> findIncomingEdges(String toNodeId) {
        String sql = "SELECT * FROM U_WF_EDGE WHERE TO_NODE_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new EdgeMapper(), toNodeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowEdge> findOutgoingEdges(String fromNodeId) {
        String sql = "SELECT * FROM U_WF_EDGE WHERE FROM_NODE_ID = ? AND DEL_FL = 'N'";
        return jdbcTemplate.query(sql, new EdgeMapper(), fromNodeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowNode> findStartNodes(String definitionId) {
        String sql = """
                SELECT n.* FROM U_WF_NODE n
                WHERE n.DEFINITION_ID = ? AND n.DEL_FL = 'N'
                  AND NOT EXISTS (
                      SELECT 1 FROM U_WF_EDGE e
                      WHERE e.TO_NODE_ID = n.ID AND e.DEL_FL = 'N'
                  )
                """;
        return jdbcTemplate.query(sql, new NodeMapper(), definitionId);
    }

    @Override
    @Transactional
    public void insertDefinition(WorkflowDefinition definition) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_DEFINITION
                  (ID, DEFINITION_NM, DESCRIPTION, VERSION_NO, ACTIVE_FL, USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, ?)
                """,
                definition.id(), definition.definitionNm(), definition.description(),
                definition.versionNo(), definition.activeFl() != null ? definition.activeFl() : "Y",
                definition.regId());
    }

    @Override
    @Transactional
    public void updateDefinitionMeta(String definitionId, String description, String activeFl) {
        jdbcTemplate.update("""
                UPDATE U_WF_DEFINITION
                SET DESCRIPTION = ?, ACTIVE_FL = COALESCE(?, ACTIVE_FL), EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, description, activeFl, definitionId);
    }

    @Override
    @Transactional
    public void softDeleteDefinition(String definitionId) {
        jdbcTemplate.update("""
                UPDATE U_WF_DEFINITION
                SET DEL_FL = 'Y', ACTIVE_FL = 'N', EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, definitionId);
    }

    @Override
    @Transactional
    public void insertNode(WorkflowNode node) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_NODE
                  (ID, DEFINITION_ID, NODE_NM, ACTIVITY_NM, INPUT_PARAMS, POS_X_NO, POS_Y_NO,
                   USE_FL, VIEW_FL, DEL_FL, REG_DT)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP)
                """,
                node.id(), node.definitionId(), node.nodeNm(), node.activityNm(),
                node.inputParams(), node.posXNo(), node.posYNo());
    }

    @Override
    @Transactional
    public void softDeleteNodesByDefinition(String definitionId) {
        jdbcTemplate.update("""
                UPDATE U_WF_NODE SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP
                WHERE DEFINITION_ID = ?
                """, definitionId);
    }

    @Override
    @Transactional
    public void insertEdge(WorkflowEdge edge) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_EDGE
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
                UPDATE U_WF_EDGE SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP
                WHERE DEFINITION_ID = ?
                """, definitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public int findMaxVersionByName(String definitionNm) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(VERSION_NO), 0) FROM U_WF_DEFINITION WHERE DEFINITION_NM = ? AND DEL_FL = 'N'",
                Integer.class, definitionNm);
        return max != null ? max : 0;
    }

    private static class DefinitionMapper implements RowMapper<WorkflowDefinition> {
        @Override
        public WorkflowDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WorkflowDefinition(
                rs.getString("ID"),
                rs.getString("DEFINITION_NM"),
                rs.getString("DESCRIPTION"),
                rs.getInt("VERSION_NO"),
                rs.getString("ACTIVE_FL"),
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

    private static class NodeMapper implements RowMapper<WorkflowNode> {
        @Override
        public WorkflowNode mapRow(ResultSet rs, int rowNum) throws SQLException {
            int posX = rs.getInt("POS_X_NO");
            Integer posXVal = rs.wasNull() ? null : posX;
            int posY = rs.getInt("POS_Y_NO");
            Integer posYVal = rs.wasNull() ? null : posY;
            return new WorkflowNode(
                rs.getString("ID"),
                rs.getString("DEFINITION_ID"),
                rs.getString("NODE_NM"),
                rs.getString("ACTIVITY_NM"),
                rs.getString("INPUT_PARAMS"),
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

    private static class EdgeMapper implements RowMapper<WorkflowEdge> {
        @Override
        public WorkflowEdge mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WorkflowEdge(
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
