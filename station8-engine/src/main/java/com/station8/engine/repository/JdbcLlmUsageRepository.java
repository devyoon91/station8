package com.station8.engine.repository;

import com.station8.engine.entity.LlmUsageEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * {@link LlmUsageRepository} JDBC 구현 (#339). h2/mariadb/oracle schema의 H_LINE_LLM_USAGE.
 */
@Repository
public class JdbcLlmUsageRepository implements LlmUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLlmUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public String insert(LlmUsageEntry entry) {
        String id = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO H_LINE_LLM_USAGE
                  (ID, INSTANCE_ID, NODE_ID, ACTIVITY_NAME, PROVIDER, MODEL,
                   INPUT_TOKENS, OUTPUT_TOKENS, ESTIMATED_COST_USD, PROMPT_HASH,
                   DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', CURRENT_TIMESTAMP, ?)
                """,
                id, entry.instanceId(), entry.nodeId(), entry.activityName(),
                entry.provider(), entry.model(),
                entry.inputTokens(), entry.outputTokens(), entry.estimatedCostUsd(), entry.promptHash(),
                entry.regId());
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LlmUsageEntry> findByInstanceId(String instanceId) {
        return jdbcTemplate.query("""
                SELECT * FROM H_LINE_LLM_USAGE
                WHERE INSTANCE_ID = ? AND DEL_FL = 'N'
                ORDER BY REG_DT ASC
                """, new Mapper(), instanceId);
    }

    private static class Mapper implements RowMapper<LlmUsageEntry> {
        @Override
        public LlmUsageEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LlmUsageEntry(
                    rs.getString("ID"),
                    rs.getString("INSTANCE_ID"),
                    rs.getString("NODE_ID"),
                    rs.getString("ACTIVITY_NAME"),
                    rs.getString("PROVIDER"),
                    rs.getString("MODEL"),
                    rs.getInt("INPUT_TOKENS"),
                    rs.getInt("OUTPUT_TOKENS"),
                    rs.getBigDecimal("ESTIMATED_COST_USD"),
                    rs.getString("PROMPT_HASH"),
                    rs.getString("DEL_FL"),
                    toLdt(rs.getTimestamp("REG_DT")),
                    rs.getString("REG_ID"),
                    toLdt(rs.getTimestamp("EDIT_DT")),
                    rs.getString("EDIT_ID")
            );
        }

        private static java.time.LocalDateTime toLdt(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }
    }
}
