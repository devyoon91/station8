package com.station8.app;

import com.station8.app.migration.DataMigrationLine;
import com.station8.engine.core.DefaultLineContext;
import com.station8.engine.core.ExponentialBackoffRetryPolicy;
import com.station8.engine.core.TaskExecutor;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = Application.class)
public class IntegrationLineE2ETest {

    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ActivityRepository activityRepository;
    @Autowired
    TaskExecutor taskExecutor;
    @Autowired
    JsonUtil jsonUtil;
    @Autowired
    DataMigrationLine dataMigrationLine;
    @Autowired
    ExponentialBackoffRetryPolicy retryPolicy;

    private String instanceId;

    @BeforeEach
    void setupSchemaAndSeed() {
        // Clean and load H2 schema + sample data (station8-engine provides schema-h2.sql)
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"),
                new ClassPathResource("sql/migration-test-data.sql")
        );
        populator.setContinueOnError(true);
        populator.execute(jdbcTemplate.getDataSource());

        // 테스트 격리: MigrationInitializer가 부팅 시 만든 인스턴스/액티비티 정리
        // (FK 순서: DLQ → ACTIVITY → INSTANCE)
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");

        // #353 — 마이그레이션 타겟/원본을 hermetic하게 초기화. 공유 H2(appdb)에서 다른 컨텍스트
        // 부팅(MigrationInitializer + @Scheduled 첫 poll)이 DEST_DATA에 id 1/3을 미리 넣으면
        // migrateItem의 INSERT가 PK 충돌로 실패해 "0 COMPLETED" flaky. 매 테스트 깨끗하게 시작.
        jdbcTemplate.execute("DELETE FROM DEST_DATA");
        jdbcTemplate.execute("UPDATE SRC_DATA SET MIGRATED_FL = 'N'");

        // Fresh instance
        instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, DEL_FL, START_DT, REG_DT)
            VALUES (?, 'DataMigrationLine', 'RUNNING', NULL, 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, instanceId);

        // Create three pending activities with known payloads
        String p1 = jsonUtil.toJson(Map.of("id", "1", "content", "First Data"));
        String p2 = jsonUtil.toJson(Map.of("id", "2", "content", "Second Data")); // will fail
        String p3 = jsonUtil.toJson(Map.of("id", "3", "content", "Third Data"));
        activityRepository.createPending(instanceId, "MIGRATION_WRITE", p1, null);
        activityRepository.createPending(instanceId, "MIGRATION_WRITE", p2, null);
        activityRepository.createPending(instanceId, "MIGRATION_WRITE", p3, null);
    }

    @Test
    void endToEnd_process_success_failWithBackoff_then_success() {
        // Lock pending -> RUNNING
        List<ActivityExecution> locked = activityRepository.findPendingActivitiesWithLock(10);
        assertEquals(3, locked.size(), "3 activities should be locked to RUNNING");

        // Process each by simulating worker invocation + TaskExecutor callbacks
        for (ActivityExecution exec : locked) {
            String inputJson = exec.inputData();
            DefaultLineContext ctx = new DefaultLineContext(
                    instanceId,
                    "DataMigrationLine",
                    "MIGRATION_WRITE",
                    exec.retryCnt() == 0 ? 1 : exec.retryCnt() + 1,
                    inputJson,
                    null,
                    jsonUtil
            );
            // attributes required by JdbcTaskExecutor
            ctx.attributes().put("executionId", exec.id());
            ctx.attributes().put("instanceId", exec.instanceId());

            try {
                String output = dataMigrationLine.migrateItem(inputJson);
                taskExecutor.complete(ctx, output);
            } catch (RuntimeException ex) {
                // backoff for attempt 1 with base 5s as per @Activity(backoffSeconds=5)
                Duration backoff = retryPolicy.calculateNextBackoff(1, 5);
                taskExecutor.fail(ctx, ex, backoff);
            }
        }

        // Verify DB state
        List<ActivityExecution> all = activityRepository.findActivitiesByInstanceId(instanceId);
        long completed = all.stream().filter(a -> "COMPLETED".equals(a.statusSt())).count();
        long failed = all.stream().filter(a -> "FAILED".equals(a.statusSt())).count();

        assertEquals(2, completed, "Two activities should be COMPLETED (First, Third)");
        assertEquals(1, failed, "One activity should be FAILED (Second)");

        ActivityExecution failedExec = all.stream().filter(a -> "FAILED".equals(a.statusSt())).findFirst().orElseThrow();
        assertNotNull(failedExec.nextRetryDt(), "Failed execution should have nextRetryDt for backoff");
        assertTrue(failedExec.nextRetryDt().isAfter(LocalDateTime.now().minusSeconds(1)), "nextRetryDt should be set in the future or now");

        // Also verify that DEST_DATA contains inserted rows for successes
        Integer destCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM DEST_DATA", Integer.class);
        assertEquals(2, destCount);

        // And SRC_DATA migrated flags updated for successes
        Integer migrated = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM SRC_DATA WHERE MIGRATED_FL='Y'", Integer.class);
        assertEquals(2, migrated);
    }
}
