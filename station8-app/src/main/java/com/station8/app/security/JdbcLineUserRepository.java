package com.station8.app.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcLineUserRepository implements LineUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLineUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public LineUser findByUsername(String username) {
        List<LineUser> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_USER WHERE USERNAME = ? AND DEL_FL = 'N'",
                new UserMapper(), username);
        if (rows.isEmpty()) return null;
        LineUser u = rows.get(0);
        return withRoles(u);
    }

    @Override
    @Transactional(readOnly = true)
    public LineUser findById(String id) {
        List<LineUser> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_USER WHERE ID = ? AND DEL_FL = 'N'",
                new UserMapper(), id);
        if (rows.isEmpty()) return null;
        return withRoles(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineUser> findAll() {
        List<LineUser> users = jdbcTemplate.query(
                "SELECT * FROM U_LINE_USER WHERE DEL_FL = 'N' ORDER BY USERNAME ASC",
                new UserMapper());
        if (users.isEmpty()) return users;
        // 일괄 role fetch — N+1 회피
        Map<String, Set<String>> rolesByUser = new HashMap<>();
        jdbcTemplate.query(
                "SELECT USER_ID, ROLE FROM U_LINE_USER_ROLE WHERE DEL_FL = 'N'",
                rs -> {
                    rolesByUser.computeIfAbsent(rs.getString("USER_ID"), k -> new HashSet<>())
                            .add(rs.getString("ROLE"));
                });
        List<LineUser> withRoles = new ArrayList<>(users.size());
        for (LineUser u : users) {
            Set<String> roles = rolesByUser.getOrDefault(u.id(), Set.of());
            withRoles.add(new LineUser(u.id(), u.username(), u.passwordHash(), u.displayNm(),
                    u.enabledFl(), roles, u.delFl(),
                    u.regDt(), u.regId(), u.editDt(), u.editId()));
        }
        return withRoles;
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM U_LINE_USER WHERE DEL_FL = 'N'", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional
    public void insert(LineUser u) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_USER
                  (ID, USERNAME, PASSWORD_HASH, DISPLAY_NM, ENABLED_FL,
                   DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, 'N', CURRENT_TIMESTAMP, ?)
                """,
                u.id(), u.username(), u.passwordHash(), u.displayNm(),
                u.enabledFl() != null ? u.enabledFl() : "Y",
                u.regId());
        Set<String> rolesToInsert = (u.roles() == null || u.roles().isEmpty())
                ? Set.of("USER") : u.roles();
        for (String role : rolesToInsert) {
            jdbcTemplate.update("""
                    INSERT INTO U_LINE_USER_ROLE
                      (ID, USER_ID, ROLE, DEL_FL, REG_DT, REG_ID)
                    VALUES (?, ?, ?, 'N', CURRENT_TIMESTAMP, ?)
                    """, UUID.randomUUID().toString(), u.id(), role, u.regId());
        }
    }

    @Override
    @Transactional
    public void updatePasswordHash(String userId, String newHash, String editId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_USER
                SET PASSWORD_HASH = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE ID = ?
                """, newHash, editId, userId);
    }

    @Override
    @Transactional
    public void updateDisplayName(String userId, String displayNm, String editId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_USER
                SET DISPLAY_NM = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE ID = ?
                """, displayNm, editId, userId);
    }

    @Override
    @Transactional
    public void setEnabled(String userId, boolean enabled, String editId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_USER
                SET ENABLED_FL = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE ID = ?
                """, enabled ? "Y" : "N", editId, userId);
    }

    @Override
    @Transactional
    public void softDelete(String userId, String editId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_USER_ROLE SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE USER_ID = ?
                """, editId, userId);
        jdbcTemplate.update("""
                UPDATE U_LINE_USER SET DEL_FL = 'Y', ENABLED_FL = 'N',
                    EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE ID = ?
                """, editId, userId);
    }

    private LineUser withRoles(LineUser u) {
        Set<String> roles = new HashSet<>(jdbcTemplate.query(
                "SELECT ROLE FROM U_LINE_USER_ROLE WHERE USER_ID = ? AND DEL_FL = 'N'",
                (rs, rowNum) -> rs.getString(1), u.id()));
        return new LineUser(u.id(), u.username(), u.passwordHash(), u.displayNm(),
                u.enabledFl(), roles, u.delFl(),
                u.regDt(), u.regId(), u.editDt(), u.editId());
    }

    private static class UserMapper implements RowMapper<LineUser> {
        @Override
        public LineUser mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LineUser(
                    rs.getString("ID"),
                    rs.getString("USERNAME"),
                    rs.getString("PASSWORD_HASH"),
                    rs.getString("DISPLAY_NM"),
                    rs.getString("ENABLED_FL"),
                    new LinkedHashMap<String, String>().keySet(),  // 빈 Set placeholder
                    rs.getString("DEL_FL"),
                    rs.getTimestamp("REG_DT") != null ? rs.getTimestamp("REG_DT").toLocalDateTime() : null,
                    rs.getString("REG_ID"),
                    rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                    rs.getString("EDIT_ID")
            );
        }
    }
}
