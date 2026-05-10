package com.station8.engine.repository;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JdbcTemplate 기반의 ActivityRepository 구현체.
 * Oracle과 MariaDB의 SKIP LOCKED 기능을 활용하여 분산 락 없이 작업 큐를 구현합니다.
 */
@Repository
public class JdbcActivityRepository implements ActivityRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DbDialect dbDialect;

    public JdbcActivityRepository(JdbcTemplate jdbcTemplate, DbDialect dbDialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbDialect = dbDialect;
    }

    /**
     * PENDING 상태이고 실행 시간이 된 작업을 SKIP LOCKED로 조회하고 즉시 RUNNING으로 업데이트합니다.
     */
    @Override
    @Transactional
    public List<ActivityExecution> findPendingActivitiesWithLock(int limit) {
        // 1. SKIP LOCKED를 사용하여 잠금된 레코드 조회
        // Oracle & MariaDB 10.6+ 공통 문법
        // LIMIT/FETCH 절은 FOR UPDATE 앞에 위치해야 모든 DB(Oracle 12c+, MariaDB 10.6+, H2 MySQL 모드)에서 동작.
        // Oracle: ORDER BY ... FETCH FIRST N ROWS ONLY FOR UPDATE SKIP LOCKED
        // MariaDB/H2: ORDER BY ... LIMIT N FOR UPDATE SKIP LOCKED
        // #139 — 인스턴스가 RUNNING 상태일 때만 활동 픽업 (PAUSED/TERMINATED/FAILED/COMPLETED는 제외)
        // EXISTS 서브쿼리로 처리 → FOR UPDATE SKIP LOCKED는 활동 행에만 걸림 (인스턴스 행 lock 회피)
        String selectSql = String.format("""
            SELECT * FROM H_LINE_ACTIVITY_EXECUTION
            WHERE STATUS_ST = 'PENDING'
              AND (NEXT_RETRY_DT IS NULL OR NEXT_RETRY_DT <= %s)
              AND USE_FL = 'Y'
              AND DEL_FL = 'N'
              AND EXISTS (
                  SELECT 1 FROM U_LINE_INSTANCE i
                  WHERE i.ID = H_LINE_ACTIVITY_EXECUTION.INSTANCE_ID
                    AND i.STATUS_ST = 'RUNNING'
              )
            ORDER BY REG_DT ASC
            %s
            FOR UPDATE SKIP LOCKED
            """, dbDialect.currentTimestamp(), dbDialect.limit(limit));
        
        List<ActivityExecution> activities = jdbcTemplate.query(selectSql, new ActivityExecutionRowMapper());
        
        if (activities.isEmpty()) {
            return activities;
        }

        // 2. 조회된 작업들의 상태를 RUNNING으로 즉시 업데이트 (다른 워커가 가로채지 못하도록)
        for (ActivityExecution activity : activities) {
            jdbcTemplate.update(
                String.format("UPDATE H_LINE_ACTIVITY_EXECUTION SET STATUS_ST = 'RUNNING', START_DT = %s, EDIT_DT = %s WHERE ID = ?",
                    dbDialect.currentTimestamp(), dbDialect.currentTimestamp()),
                activity.id()
            );
        }

        // 호출자(LineWorker)가 statusSt에 의존할 수 있으므로 반환 객체를 RUNNING으로 재구성
        return activities.stream().map(a -> a.withStatus("RUNNING")).toList();
    }

    @Override
    @Transactional
    public void updateStatus(ActivityExecution activity) {
        String sql = String.format("""
            UPDATE H_LINE_ACTIVITY_EXECUTION
            SET STATUS_ST = ?,
                OUTPUT_DATA = ?,
                ERROR_MESSAGE = ?,
                STACK_TRACE = ?,
                RETRY_CNT = ?,
                NEXT_RETRY_DT = ?,
                END_DT = ?,
                EDIT_DT = %s
            WHERE ID = ?
            """, dbDialect.currentTimestamp());
        
        jdbcTemplate.update(sql,
            activity.statusSt(),
            activity.outputData(),
            activity.errorMessage(),
            activity.stackTrace(),
            activity.retryCnt(),
            activity.nextRetryDt(),
            activity.endDt(),
            activity.id()
        );
    }

    @Override
    @Transactional
    public String createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt) {
        String id = UUID.randomUUID().toString();
        String sql = String.format("""
            INSERT INTO H_LINE_ACTIVITY_EXECUTION (
                ID, INSTANCE_ID, NODE_ID, ACTIVITY_NAME, STATUS_ST, INPUT_DATA,
                RETRY_CNT, NEXT_RETRY_DT, USE_FL, VIEW_FL, DEL_FL, REG_DT
            ) VALUES (
                ?, ?, NULL, ?, 'PENDING', ?,
                0, ?, 'Y', 'Y', 'N', %s
            )
            """, dbDialect.currentTimestamp());
        jdbcTemplate.update(sql,
            id,
            instanceId,
            activityName,
            inputData,
            nextRetryDt
        );
        return id;
    }

    @Override
    @Transactional
    public String createForNode(String instanceId, String nodeId, String activityName, String statusSt, String inputData) {
        String id = UUID.randomUUID().toString();
        String sql = String.format("""
            INSERT INTO H_LINE_ACTIVITY_EXECUTION (
                ID, INSTANCE_ID, NODE_ID, ACTIVITY_NAME, STATUS_ST, INPUT_DATA,
                RETRY_CNT, USE_FL, VIEW_FL, DEL_FL, REG_DT
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                0, 'Y', 'Y', 'N', %s
            )
            """, dbDialect.currentTimestamp());
        jdbcTemplate.update(sql, id, instanceId, nodeId, activityName, statusSt, inputData);
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityExecution findById(String executionId) {
        String sql = "SELECT * FROM H_LINE_ACTIVITY_EXECUTION WHERE ID = ?";
        List<ActivityExecution> rows = jdbcTemplate.query(sql, new ActivityExecutionRowMapper(), executionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityExecution findByInstanceAndNode(String instanceId, String nodeId) {
        String sql = "SELECT * FROM H_LINE_ACTIVITY_EXECUTION WHERE INSTANCE_ID = ? AND NODE_ID = ?";
        List<ActivityExecution> rows = jdbcTemplate.query(sql, new ActivityExecutionRowMapper(), instanceId, nodeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional
    public void promoteToPending(String executionId) {
        String sql = String.format("""
            UPDATE H_LINE_ACTIVITY_EXECUTION
            SET STATUS_ST = 'PENDING',
                EDIT_DT = %s
            WHERE ID = ? AND STATUS_ST = 'WAITING_DEPENDENCIES'
            """, dbDialect.currentTimestamp());
        jdbcTemplate.update(sql, executionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineInstance> findAllInstances() {
        String sql = "SELECT * FROM U_LINE_INSTANCE ORDER BY REG_DT DESC";
        return jdbcTemplate.query(sql, new LineInstanceRowMapper());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LineInstance> findInstancesPage(InstanceQueryFilter filter, int offset, int limit) {
        InstanceFilter f = new InstanceFilter(filter);
        String sql = "SELECT * FROM U_LINE_INSTANCE " + f.where()
                + " ORDER BY " + filter.sortBy() + " " + filter.sortDir()
                + " " + dbDialect.offsetLimit(offset, limit);
        return jdbcTemplate.query(sql, new LineInstanceRowMapper(), f.args().toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public long countInstances(InstanceQueryFilter filter) {
        InstanceFilter f = new InstanceFilter(filter);
        String sql = "SELECT COUNT(*) FROM U_LINE_INSTANCE " + f.where();
        Long n = jdbcTemplate.queryForObject(sql, Long.class, f.args().toArray());
        return n == null ? 0L : n;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> countInstancesByStatus() {
        String sql = "SELECT STATUS_ST, COUNT(*) FROM U_LINE_INSTANCE GROUP BY STATUS_ST";
        Map<String, Long> out = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    /**
     * Dashboard 필터를 동적 WHERE로 빌드 (#97 + #137).
     * 빈/null 인자는 무시. SQL 인젝션 방지를 위해 모두 ``?``로 바인딩한다.
     * sortBy/sortDir은 {@link InstanceQueryFilter} 컴팩트 생성자에서 화이트리스트 정규화됨.
     */
    private static final class InstanceFilter {
        private final List<String> conditions = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();
        InstanceFilter(InstanceQueryFilter f) {
            if (f.workflowName() != null && !f.workflowName().isBlank()) {
                conditions.add("WORKFLOW_NAME LIKE ?");
                args.add("%" + f.workflowName() + "%");
            }
            // #137 — 다중 status: STATUS_ST IN (?, ?, ...)
            if (f.statusList() != null && !f.statusList().isEmpty()) {
                String placeholders = f.statusList().stream()
                        .map(s -> "?")
                        .collect(java.util.stream.Collectors.joining(", "));
                conditions.add("STATUS_ST IN (" + placeholders + ")");
                args.addAll(f.statusList());
            }
            if (f.instanceId() != null && !f.instanceId().isBlank()) {
                conditions.add("ID LIKE ?");
                args.add("%" + f.instanceId() + "%");
            }
            // #137 — 날짜 범위 (inclusive 양 끝, 컨트롤러가 to를 23:59:59로 확장)
            if (f.startDtFrom() != null) {
                conditions.add("START_DT >= ?");
                args.add(java.sql.Timestamp.valueOf(f.startDtFrom()));
            }
            if (f.startDtTo() != null) {
                conditions.add("START_DT <= ?");
                args.add(java.sql.Timestamp.valueOf(f.startDtTo()));
            }
            // #159 — ACL READ 가시성 필터: WORKFLOW_NAME IN (?, ?, ...)
            if (f.workflowNameAllowList() != null) {
                if (f.workflowNameAllowList().isEmpty()) {
                    // 빈 set → 0행 보장: 항상 false
                    conditions.add("1=0");
                } else {
                    String placeholders = f.workflowNameAllowList().stream()
                            .map(s -> "?")
                            .collect(java.util.stream.Collectors.joining(", "));
                    conditions.add("WORKFLOW_NAME IN (" + placeholders + ")");
                    args.addAll(f.workflowNameAllowList());
                }
            }
        }
        String where() {
            return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        }
        List<Object> args() { return args; }
    }

    @Override
    @Transactional(readOnly = true)
    public LineInstance findInstanceById(String instanceId) {
        String sql = "SELECT * FROM U_LINE_INSTANCE WHERE ID = ?";
        return jdbcTemplate.queryForObject(sql, new LineInstanceRowMapper(), instanceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityExecution> findActivitiesByInstanceId(String instanceId) {
        String sql = "SELECT * FROM H_LINE_ACTIVITY_EXECUTION WHERE INSTANCE_ID = ? ORDER BY REG_DT ASC";
        return jdbcTemplate.query(sql, new ActivityExecutionRowMapper(), instanceId);
    }

    @Override
    @Transactional
    public void resetToPending(String executionId) {
        String sql = String.format("""
            UPDATE H_LINE_ACTIVITY_EXECUTION
            SET STATUS_ST = 'PENDING',
                NEXT_RETRY_DT = NULL,
                START_DT = NULL,
                END_DT = NULL,
                EDIT_DT = %s
            WHERE ID = ?
            """, dbDialect.currentTimestamp());
        jdbcTemplate.update(sql, executionId);
    }

    // ========== #164 — Pipeline 게이트 지원 ==========

    @Override
    @Transactional(readOnly = true)
    public boolean isNodeCompleted(String instanceId, String nodeId) {
        String sql = """
            SELECT COUNT(*) FROM H_LINE_ACTIVITY_EXECUTION
            WHERE INSTANCE_ID = ? AND NODE_ID = ? AND STATUS_ST = 'COMPLETED' AND DEL_FL = 'N'
            """;
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, instanceId, nodeId);
        return n != null && n > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAnyNodeStarted(String instanceId, java.util.Collection<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return false;
        String placeholders = nodeIds.stream().map(s -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT COUNT(*) FROM H_LINE_ACTIVITY_EXECUTION "
                + "WHERE INSTANCE_ID = ? AND NODE_ID IN (" + placeholders + ") "
                + "AND STATUS_ST IN ('RUNNING', 'COMPLETED', 'FAILED', 'FAILED_FINAL') "
                + "AND DEL_FL = 'N'";
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(instanceId);
        args.addAll(nodeIds);
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        return n != null && n > 0;
    }

    @Override
    @Transactional
    public void revertGateBlocked(String executionId, LocalDateTime nextRetryDt) {
        String sql = String.format("""
            UPDATE H_LINE_ACTIVITY_EXECUTION
            SET STATUS_ST = 'PENDING',
                START_DT = NULL,
                NEXT_RETRY_DT = ?,
                EDIT_DT = %s,
                EDIT_ID = 'pipeline-gate'
            WHERE ID = ?
            """, dbDialect.currentTimestamp());
        jdbcTemplate.update(sql,
                nextRetryDt == null ? null : java.sql.Timestamp.valueOf(nextRetryDt),
                executionId);
    }

    @Override
    @Transactional
    public int bulkUpdateNotStartedStatuses(String instanceId, String toStatus) {
        // RUNNING/COMPLETED/FAILED는 영향 없음 — 워커 자연 완료 또는 이미 종결된 것
        String sql = String.format("""
            UPDATE H_LINE_ACTIVITY_EXECUTION
            SET STATUS_ST = ?,
                END_DT = %s,
                EDIT_DT = %s,
                EDIT_ID = 'terminate'
            WHERE INSTANCE_ID = ?
              AND STATUS_ST IN ('PENDING', 'WAITING_DEPENDENCIES')
            """, dbDialect.currentTimestamp(), dbDialect.currentTimestamp());
        return jdbcTemplate.update(sql, toStatus, instanceId);
    }

    private static class LineInstanceRowMapper implements RowMapper<LineInstance> {
        @Override
        public LineInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LineInstance(
                rs.getString("ID"),
                rs.getString("WORKFLOW_NAME"),
                rs.getString("STATUS_ST"),
                rs.getString("INPUT_DATA"),
                rs.getString("OUTPUT_DATA"),
                rs.getString("STATE_DATA"),
                rs.getString("RUN_OPTIONS"),
                rs.getTimestamp("START_DT") != null ? rs.getTimestamp("START_DT").toLocalDateTime() : null,
                rs.getTimestamp("END_DT") != null ? rs.getTimestamp("END_DT").toLocalDateTime() : null,
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT").toLocalDateTime(),
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }

    private static class ActivityExecutionRowMapper implements RowMapper<ActivityExecution> {
        @Override
        public ActivityExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ActivityExecution(
                rs.getString("ID"),
                rs.getString("INSTANCE_ID"),
                rs.getString("NODE_ID"),
                rs.getString("ACTIVITY_NAME"),
                rs.getString("STATUS_ST"),
                rs.getString("INPUT_DATA"),
                rs.getString("OUTPUT_DATA"),
                rs.getString("ERROR_MESSAGE"),
                rs.getString("STACK_TRACE"),
                rs.getInt("RETRY_CNT"),
                rs.getTimestamp("NEXT_RETRY_DT") != null ? rs.getTimestamp("NEXT_RETRY_DT").toLocalDateTime() : null,
                rs.getTimestamp("START_DT") != null ? rs.getTimestamp("START_DT").toLocalDateTime() : null,
                rs.getTimestamp("END_DT") != null ? rs.getTimestamp("END_DT").toLocalDateTime() : null,
                rs.getString("USE_FL"),
                rs.getString("VIEW_FL"),
                rs.getString("DEL_FL"),
                rs.getTimestamp("REG_DT").toLocalDateTime(),
                rs.getString("REG_ID"),
                rs.getTimestamp("EDIT_DT") != null ? rs.getTimestamp("EDIT_DT").toLocalDateTime() : null,
                rs.getString("EDIT_ID")
            );
        }
    }
}

