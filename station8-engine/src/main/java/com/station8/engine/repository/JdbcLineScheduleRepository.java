package com.station8.engine.repository;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.LineSchedule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class JdbcLineScheduleRepository implements LineScheduleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DbDialect dbDialect;

    public JdbcLineScheduleRepository(JdbcTemplate jdbcTemplate, DbDialect dbDialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbDialect = dbDialect;
    }

    @Override
    @Transactional
    public void insert(LineSchedule s) {
        jdbcTemplate.update("""
                INSERT INTO U_WF_SCHEDULE
                  (ID, DEFINITION_ID, CRON_EXPR, NEXT_RUN_DT, LAST_RUN_DT,
                   PAUSED_FL, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, ?)
                """,
                s.id(), s.definitionId(), s.cronExpr(),
                s.nextRunDt(), s.lastRunDt(),
                s.pausedFl() != null ? s.pausedFl() : "N",
                s.inputData(), s.regId());
    }

    @Override
    @Transactional(readOnly = true)
    public LineSchedule findById(String id) {
        List<LineSchedule> rows = jdbcTemplate.query(
                "SELECT * FROM U_WF_SCHEDULE WHERE ID = ? AND DEL_FL = 'N'",
                new ScheduleMapper(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineSchedule> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM U_WF_SCHEDULE WHERE DEL_FL = 'N' ORDER BY REG_DT DESC",
                new ScheduleMapper());
    }

    @Override
    @Transactional
    public List<LineSchedule> findDueWithLock(int limit) {
        // M2-2 폴러가 사용. PAUSED_FL='N' AND NEXT_RUN_DT <= NOW() 조건 + SKIP LOCKED
        String sql = String.format("""
                SELECT * FROM U_WF_SCHEDULE
                WHERE PAUSED_FL = 'N'
                  AND DEL_FL = 'N'
                  AND USE_FL = 'Y'
                  AND NEXT_RUN_DT IS NOT NULL
                  AND NEXT_RUN_DT <= %s
                ORDER BY NEXT_RUN_DT ASC
                %s
                FOR UPDATE SKIP LOCKED
                """, dbDialect.currentTimestamp(), dbDialect.limit(limit));
        return jdbcTemplate.query(sql, new ScheduleMapper());
    }

    @Override
    @Transactional
    public void markRun(String scheduleId, LocalDateTime nextRunDt, LocalDateTime lastRunDt) {
        jdbcTemplate.update("""
                UPDATE U_WF_SCHEDULE
                SET NEXT_RUN_DT = ?, LAST_RUN_DT = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = 'scheduler'
                WHERE ID = ?
                """, nextRunDt, lastRunDt, scheduleId);
    }

    @Override
    @Transactional
    public void updateCron(String scheduleId, String cronExpr, LocalDateTime nextRunDt) {
        jdbcTemplate.update("""
                UPDATE U_WF_SCHEDULE
                SET CRON_EXPR = ?, NEXT_RUN_DT = ?, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, cronExpr, nextRunDt, scheduleId);
    }

    @Override
    @Transactional
    public void setPaused(String scheduleId, boolean paused) {
        jdbcTemplate.update("""
                UPDATE U_WF_SCHEDULE
                SET PAUSED_FL = ?, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, paused ? "Y" : "N", scheduleId);
    }

    @Override
    @Transactional
    public void softDelete(String scheduleId) {
        jdbcTemplate.update("""
                UPDATE U_WF_SCHEDULE
                SET DEL_FL = 'Y', PAUSED_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, scheduleId);
    }

    private static class ScheduleMapper implements RowMapper<LineSchedule> {
        @Override
        public LineSchedule mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LineSchedule(
                    rs.getString("ID"),
                    rs.getString("DEFINITION_ID"),
                    rs.getString("CRON_EXPR"),
                    rs.getTimestamp("NEXT_RUN_DT") != null ? rs.getTimestamp("NEXT_RUN_DT").toLocalDateTime() : null,
                    rs.getTimestamp("LAST_RUN_DT") != null ? rs.getTimestamp("LAST_RUN_DT").toLocalDateTime() : null,
                    rs.getString("PAUSED_FL"),
                    rs.getString("INPUT_DATA"),
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
