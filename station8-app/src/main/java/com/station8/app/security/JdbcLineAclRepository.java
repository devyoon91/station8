package com.station8.app.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * #140 — JDBC 기반 ACL 리포지토리.
 */
@Repository
public class JdbcLineAclRepository implements LineAclRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLineAclRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void grant(String definitionId, String userId, String permission, String regId) {
        // UNIQUE (definition_id, user_id, permission) — 중복은 idempotent 무시
        try {
            jdbcTemplate.update("""
                INSERT INTO U_LINE_DEFINITION_ACL
                  (ID, DEFINITION_ID, USER_ID, PERMISSION,
                   USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, ?)
                """, UUID.randomUUID().toString(), definitionId, userId, permission, regId);
        } catch (DuplicateKeyException ex) {
            // 이미 grant 됨 — idempotent
        }
    }

    @Override
    public void revoke(String definitionId, String userId, String permission) {
        jdbcTemplate.update("""
            DELETE FROM U_LINE_DEFINITION_ACL
            WHERE DEFINITION_ID = ? AND USER_ID = ? AND PERMISSION = ?
            """, definitionId, userId, permission);
    }

    @Override
    public List<LineAclEntry> findByDefinition(String definitionId) {
        return jdbcTemplate.query("""
            SELECT * FROM U_LINE_DEFINITION_ACL
            WHERE DEFINITION_ID = ? AND DEL_FL = 'N'
            ORDER BY USER_ID, PERMISSION
            """, new AclMapper(), definitionId);
    }

    @Override
    public List<String> findPermissionsForUser(String definitionId, String userId) {
        return jdbcTemplate.queryForList("""
            SELECT PERMISSION FROM U_LINE_DEFINITION_ACL
            WHERE DEFINITION_ID = ? AND USER_ID = ? AND DEL_FL = 'N'
            """, String.class, definitionId, userId);
    }

    @Override
    public int countAdminsForDefinition(String definitionId) {
        Integer n = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM U_LINE_DEFINITION_ACL
            WHERE DEFINITION_ID = ? AND PERMISSION = 'ADMIN' AND DEL_FL = 'N'
            """, Integer.class, definitionId);
        return n == null ? 0 : n;
    }

    @Override
    public int countEntriesForDefinition(String definitionId) {
        Integer n = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM U_LINE_DEFINITION_ACL
            WHERE DEFINITION_ID = ? AND DEL_FL = 'N'
            """, Integer.class, definitionId);
        return n == null ? 0 : n;
    }

    @Override
    public Set<String> findDefinitionIdsWithAcl() {
        List<String> rows = jdbcTemplate.queryForList("""
            SELECT DISTINCT DEFINITION_ID FROM U_LINE_DEFINITION_ACL WHERE DEL_FL = 'N'
            """, String.class);
        return new HashSet<>(rows);
    }

    @Override
    public Set<String> findDefinitionIdsForUser(String userId) {
        List<String> rows = jdbcTemplate.queryForList("""
            SELECT DISTINCT DEFINITION_ID FROM U_LINE_DEFINITION_ACL
            WHERE USER_ID = ? AND DEL_FL = 'N'
            """, String.class, userId);
        return new HashSet<>(rows);
    }

    private static class AclMapper implements RowMapper<LineAclEntry> {
        @Override
        public LineAclEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LineAclEntry(
                    rs.getString("ID"),
                    rs.getString("DEFINITION_ID"),
                    rs.getString("USER_ID"),
                    rs.getString("PERMISSION"),
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
