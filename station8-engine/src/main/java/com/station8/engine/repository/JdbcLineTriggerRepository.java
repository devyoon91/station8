package com.station8.engine.repository;

import com.station8.engine.entity.LineTrigger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * M20 (#310) — JDBC 구현. h2/mariadb/oracle schema의 U_LINE_TRIGGER.
 */
@Repository
public class JdbcLineTriggerRepository implements LineTriggerRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLineTriggerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public LineTrigger findByKey(String triggerKey) {
        List<LineTrigger> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_TRIGGER WHERE TRIGGER_KEY = ? AND DEL_FL = 'N'",
                new Mapper(), triggerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public LineTrigger findById(String id) {
        List<LineTrigger> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_TRIGGER WHERE ID = ? AND DEL_FL = 'N'",
                new Mapper(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineTrigger> findAllActive() {
        return jdbcTemplate.query(
                "SELECT * FROM U_LINE_TRIGGER WHERE DEL_FL = 'N' AND ACTIVE_FL = 'Y' "
                        + "ORDER BY TRIGGER_KEY ASC",
                new Mapper());
    }

    @Override
    @Transactional
    public void insert(LineTrigger t) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_TRIGGER
                  (ID, DEFINITION_ID, TRIGGER_TYPE, TRIGGER_KEY, CONFIG_JSON,
                   ACTIVE_FL, DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, ?, 'N', CURRENT_TIMESTAMP, ?)
                """,
                t.id(), t.definitionId(), t.triggerType(), t.triggerKey(), t.configJson(),
                t.activeFl(), t.regId());
    }

    @Override
    @Transactional
    public void update(LineTrigger t) {
        jdbcTemplate.update("""
                UPDATE U_LINE_TRIGGER
                   SET DEFINITION_ID = ?, TRIGGER_TYPE = ?, TRIGGER_KEY = ?,
                       CONFIG_JSON = ?, ACTIVE_FL = ?,
                       EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                 WHERE ID = ? AND DEL_FL = 'N'
                """,
                t.definitionId(), t.triggerType(), t.triggerKey(),
                t.configJson(), t.activeFl(), t.editId(),
                t.id());
    }

    @Override
    @Transactional
    public void softDelete(String id, String editId) {
        jdbcTemplate.update(
                "UPDATE U_LINE_TRIGGER SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ? "
                        + "WHERE ID = ?",
                editId, id);
    }

    private static class Mapper implements RowMapper<LineTrigger> {
        @Override
        public LineTrigger mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp regDt = rs.getTimestamp("REG_DT");
            Timestamp editDt = rs.getTimestamp("EDIT_DT");
            return new LineTrigger(
                    rs.getString("ID"),
                    rs.getString("DEFINITION_ID"),
                    rs.getString("TRIGGER_TYPE"),
                    rs.getString("TRIGGER_KEY"),
                    rs.getString("CONFIG_JSON"),
                    rs.getString("ACTIVE_FL"),
                    rs.getString("DEL_FL"),
                    regDt == null ? null : regDt.toLocalDateTime(),
                    rs.getString("REG_ID"),
                    editDt == null ? null : editDt.toLocalDateTime(),
                    rs.getString("EDIT_ID"));
        }
    }
}
