package com.station8.engine.repository;

import com.station8.engine.entity.LineProject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * #168 — {@link LineProjectRepository}의 JDBC 구현.
 *
 * <p>cross-DB 호환 SQL — H2 / MariaDB / Oracle 모두 {@code U_LINE_PROJECT} 동일 스키마 가정.</p>
 */
@Repository
public class JdbcLineProjectRepository implements LineProjectRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLineProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public LineProject findById(String projectId) {
        List<LineProject> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_PROJECT WHERE ID = ? AND DEL_FL = 'N'",
                new ProjectMapper(), projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public LineProject findByName(String projectNm) {
        List<LineProject> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_PROJECT WHERE PROJECT_NM = ? AND DEL_FL = 'N'",
                new ProjectMapper(), projectNm);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineProject> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM U_LINE_PROJECT WHERE DEL_FL = 'N' ORDER BY PROJECT_NM ASC",
                new ProjectMapper());
    }

    @Override
    @Transactional
    public void insert(LineProject project) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_PROJECT
                  (ID, PROJECT_NM, DESCRIPTION, USE_FL, VIEW_FL, DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, ?)
                """,
                project.id(), project.projectNm(), project.description(),
                project.regId() != null ? project.regId() : "system");
    }

    @Override
    @Transactional
    public void updateMeta(String projectId, String projectNm, String description, String editId) {
        jdbcTemplate.update("""
                UPDATE U_LINE_PROJECT
                SET PROJECT_NM = ?, DESCRIPTION = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                WHERE ID = ? AND DEL_FL = 'N'
                """, projectNm, description, editId, projectId);
    }

    @Override
    @Transactional
    public void softDelete(String projectId) {
        jdbcTemplate.update(
                "UPDATE U_LINE_PROJECT SET DEL_FL = 'Y', EDIT_DT = CURRENT_TIMESTAMP "
                        + "WHERE ID = ? AND DEL_FL = 'N'",
                projectId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByName(String projectNm) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM U_LINE_PROJECT WHERE PROJECT_NM = ? AND DEL_FL = 'N'",
                Long.class, projectNm);
        return n != null && n > 0;
    }

    private static class ProjectMapper implements RowMapper<LineProject> {
        @Override
        public LineProject mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LineProject(
                    rs.getString("ID"),
                    rs.getString("PROJECT_NM"),
                    rs.getString("DESCRIPTION"),
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
