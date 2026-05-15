package com.station8.engine.repository;

import com.station8.engine.entity.DataSourceDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class JdbcDataSourceDefinitionRepository implements DataSourceDefinitionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDataSourceDefinitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void insert(DataSourceDefinition d) {
        jdbcTemplate.update("""
                INSERT INTO U_LINE_DATASOURCE
                  (ID, NAME, JDBC_URL, USERNAME, PASSWORD, DRIVER_CLASS, DIALECT, HIKARI_OPTIONS,
                   ENABLED_FL, DEL_FL, REG_DT, REG_ID)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', CURRENT_TIMESTAMP, ?)
                """,
                d.id(), d.name(), d.jdbcUrl(), d.username(), d.password(),
                d.driverClass(), d.dialect(), d.hikariOptions(),
                d.enabledFl() != null ? d.enabledFl() : "Y",
                d.regId());
    }

    @Override
    @Transactional(readOnly = true)
    public DataSourceDefinition findById(String id) {
        List<DataSourceDefinition> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_DATASOURCE WHERE ID = ? AND DEL_FL = 'N'",
                new Mapper(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public DataSourceDefinition findByName(String name) {
        List<DataSourceDefinition> rows = jdbcTemplate.query(
                "SELECT * FROM U_LINE_DATASOURCE WHERE NAME = ? AND DEL_FL = 'N'",
                new Mapper(), name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataSourceDefinition> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM U_LINE_DATASOURCE WHERE DEL_FL = 'N' ORDER BY NAME ASC",
                new Mapper());
    }

    @Override
    @Transactional
    public void update(DataSourceDefinition d, boolean keepPasswordIfBlank) {
        if (keepPasswordIfBlank && (d.password() == null || d.password().isEmpty())) {
            jdbcTemplate.update("""
                    UPDATE U_LINE_DATASOURCE
                    SET JDBC_URL = ?, USERNAME = ?, DRIVER_CLASS = ?, DIALECT = ?, HIKARI_OPTIONS = ?,
                        ENABLED_FL = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                    WHERE ID = ?
                    """,
                    d.jdbcUrl(), d.username(), d.driverClass(), d.dialect(), d.hikariOptions(),
                    d.enabledFl() != null ? d.enabledFl() : "Y",
                    d.editId(), d.id());
        } else {
            jdbcTemplate.update("""
                    UPDATE U_LINE_DATASOURCE
                    SET JDBC_URL = ?, USERNAME = ?, PASSWORD = ?, DRIVER_CLASS = ?, DIALECT = ?,
                        HIKARI_OPTIONS = ?, ENABLED_FL = ?, EDIT_DT = CURRENT_TIMESTAMP, EDIT_ID = ?
                    WHERE ID = ?
                    """,
                    d.jdbcUrl(), d.username(), d.password(), d.driverClass(), d.dialect(),
                    d.hikariOptions(),
                    d.enabledFl() != null ? d.enabledFl() : "Y",
                    d.editId(), d.id());
        }
    }

    @Override
    @Transactional
    public void setEnabled(String id, boolean enabled) {
        jdbcTemplate.update("""
                UPDATE U_LINE_DATASOURCE
                SET ENABLED_FL = ?, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, enabled ? "Y" : "N", id);
    }

    @Override
    @Transactional
    public void softDelete(String id) {
        jdbcTemplate.update("""
                UPDATE U_LINE_DATASOURCE
                SET DEL_FL = 'Y', ENABLED_FL = 'N', EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, id);
    }

    private static class Mapper implements RowMapper<DataSourceDefinition> {
        @Override
        public DataSourceDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DataSourceDefinition(
                    rs.getString("ID"),
                    rs.getString("NAME"),
                    rs.getString("JDBC_URL"),
                    rs.getString("USERNAME"),
                    rs.getString("PASSWORD"),
                    rs.getString("DRIVER_CLASS"),
                    rs.getString("DIALECT"),
                    rs.getString("HIKARI_OPTIONS"),
                    rs.getString("ENABLED_FL"),
                    rs.getString("DEL_FL"),
                    rs.getTimestamp("REG_DT") != null ? rs.getTimestamp("REG_DT").toLocalDateTime() : null,
                    rs.getString("REG_ID"),
                    rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                    rs.getString("EDIT_ID")
            );
        }
    }
}
