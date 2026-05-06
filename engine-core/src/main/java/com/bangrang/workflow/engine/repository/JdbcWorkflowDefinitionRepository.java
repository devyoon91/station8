package com.bangrang.workflow.engine.repository;

import com.bangrang.workflow.engine.entity.WorkflowDefinition;
import com.bangrang.workflow.engine.entity.WorkflowEdge;
import com.bangrang.workflow.engine.entity.WorkflowNode;
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
