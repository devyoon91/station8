package com.bangrang.workflow.engine.repository;

import com.bangrang.workflow.engine.dialect.MariaDbDialect;
import com.bangrang.workflow.engine.entity.ActivityExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class JdbcActivityRepositoryTest {

    private static JdbcTemplate jdbcTemplate;
    private JdbcActivityRepository repository;

    @BeforeAll
    static void setup() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:workflow;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 테이블 생성 (H2 용)
        jdbcTemplate.execute("CREATE TABLE U_WF_INSTANCE (" +
                "ID VARCHAR(50), WORKFLOW_NAME VARCHAR(100), STATUS_ST VARCHAR(20), " +
                "INPUT_DATA CLOB, OUTPUT_DATA CLOB, STATE_DATA CLOB, " +
                "START_DT TIMESTAMP, END_DT TIMESTAMP, USE_FL VARCHAR(1), VIEW_FL VARCHAR(1), " +
                "DEL_FL VARCHAR(1), REG_DT TIMESTAMP, REG_ID VARCHAR(32), EDIT_DT TIMESTAMP, EDIT_ID VARCHAR(32), " +
                "PRIMARY KEY (ID))");

        jdbcTemplate.execute("CREATE TABLE H_WF_ACTIVITY_EXECUTION (" +
                "ID VARCHAR(50), INSTANCE_ID VARCHAR(50), NODE_ID VARCHAR(50), " +
                "ACTIVITY_NAME VARCHAR(100), STATUS_ST VARCHAR(30), " +
                "INPUT_DATA CLOB, OUTPUT_DATA CLOB, ERROR_MESSAGE CLOB, STACK_TRACE CLOB, " +
                "RETRY_CNT INT, NEXT_RETRY_DT TIMESTAMP, START_DT TIMESTAMP, END_DT TIMESTAMP, " +
                "USE_FL VARCHAR(1), VIEW_FL VARCHAR(1), DEL_FL VARCHAR(1), REG_DT TIMESTAMP, REG_ID VARCHAR(32), " +
                "EDIT_DT TIMESTAMP, EDIT_ID VARCHAR(32), PRIMARY KEY (ID))");
    }

    @BeforeEach
    void init() {
        repository = new JdbcActivityRepository(jdbcTemplate, new com.bangrang.workflow.engine.dialect.DbDialect() {
            @Override public String limit(int limit) { return " FETCH FIRST " + limit + " ROWS ONLY"; }
            @Override public String currentTimestamp() { return "CURRENT_TIMESTAMP"; }
        });
        jdbcTemplate.execute("DELETE FROM H_WF_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_WF_INSTANCE");
    }

    @Test
    void testCreateAndFindPendingWithLock() {
        String instanceId = UUID.randomUUID().toString();
        // 인스턴스 먼저 생성 (FK 제약은 여기서는 무시하거나 생성)
        jdbcTemplate.update("INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, REG_DT) VALUES (?, ?, ?, ?)",
                instanceId, "TestWorkflow", "RUNNING", LocalDateTime.now());

        repository.createPending(instanceId, "Activity1", "{\"key\":\"val\"}", null);

        List<ActivityExecution> pending = repository.findPendingActivitiesWithLock(10);
        assertEquals(1, pending.size());
        assertEquals("Activity1", pending.get(0).activityName());
        assertEquals("RUNNING", pending.get(0).statusSt()); // findPendingActivitiesWithLock는 RUNNING으로 상태를 바꾼 후 반환함
    }

    @Test
    void testUpdateStatus() {
        String instanceId = UUID.randomUUID().toString();
        String executionId = UUID.randomUUID().toString();
        
        jdbcTemplate.update("INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, REG_DT) VALUES (?, ?, ?, ?)",
                instanceId, "TestWorkflow", "RUNNING", LocalDateTime.now());
        
        jdbcTemplate.update("INSERT INTO H_WF_ACTIVITY_EXECUTION (ID, INSTANCE_ID, ACTIVITY_NAME, STATUS_ST, REG_DT) VALUES (?, ?, ?, ?, ?)",
                executionId, instanceId, "Activity1", "RUNNING", LocalDateTime.now());

        ActivityExecution update = new ActivityExecution(
                executionId, instanceId, null, "Activity1", "COMPLETED",
                null, "{\"result\":\"ok\"}", null, null, 0, null, null, LocalDateTime.now(),
                "Y", "Y", "N", LocalDateTime.now(), "system", LocalDateTime.now(), "engine"
        );

        repository.updateStatus(update);

        ActivityExecution found = repository.findActivitiesByInstanceId(instanceId).get(0);
        assertEquals("COMPLETED", found.statusSt());
        assertEquals("{\"result\":\"ok\"}", found.outputData());
    }
}
