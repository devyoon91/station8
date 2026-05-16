package com.station8.engine.repository;

import com.station8.engine.entity.Credential;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * M17 (#270) — JDBC 구현. h2/mariadb/oracle schema의 U_LINE_CREDENTIAL.
 */
@Repository
public class JdbcCredentialRepository implements CredentialRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void insert(Credential c) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_CREDENTIAL
                  (ID, NAME, TYPE, VALUE_ENC, SCHEMA_JSON,
                   DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, 'N', CURRENT_TIMESTAMP, ?)
                """,
                c.id(), c.name(), c.type(), c.valueEnc(), c.schemaJson(),
                c.regId());
    }

    @Override
    @Transactional(readOnly = true)
    public Credential findById(String id) {
        List<Credential> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_CREDENTIAL WHERE ID = ? AND DEL_FL = 'N'",
                new Mapper(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Credential findByName(String name) {
        List<Credential> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_CREDENTIAL WHERE NAME = ? AND DEL_FL = 'N'",
                new Mapper(), name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Credential> findAllActive() {
        return jdbcTemplate.query("""
                SELECT * FROM U_LINE_CREDENTIAL
                WHERE DEL_FL = 'N'
                ORDER BY NAME ASC
                """, new Mapper());
    }

    @Override
    @Transactional
    public void update(Credential c) {
        jdbcTemplate.update("""
                UPDATE U_LINE_CREDENTIAL
                SET NAME = ?, TYPE = ?, VALUE_ENC = ?, SCHEMA_JSON = ?,
                    EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE ID = ? AND DEL_FL = 'N'
                """,
                c.name(), c.type(), c.valueEnc(), c.schemaJson(),
                c.editId(), c.id());
    }

    @Override
    @Transactional
    public void softDelete(String id, String editId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_CREDENTIAL
                SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE ID = ? AND DEL_FL = 'N'
                """, editId, id);
    }

    private static class Mapper implements RowMapper<Credential> {
        @Override
        public Credential mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Credential(
                    rs.getString("ID"),
                    rs.getString("NAME"),
                    rs.getString("TYPE"),
                    rs.getString("VALUE_ENC"),
                    rs.getString("SCHEMA_JSON"),
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
