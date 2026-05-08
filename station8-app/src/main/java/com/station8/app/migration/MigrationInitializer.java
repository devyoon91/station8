package com.station8.app.migration;

import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 데모 초기화 컴포넌트: 테스트 테이블/데이터 생성 후, 마이그레이션 작업을 PENDING으로 등록한다.
 *
 * Order 0 — DemoSeedRunner(@Order(100))보다 먼저 실행되어 schema를 적용한다.
 * 미적용 시 후속 Runner들이 ``Table doesn't exist`` 에러를 만난다.
 */
@Component
@org.springframework.core.annotation.Order(0)
public class MigrationInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final ActivityRepository activityRepository;
    private final JsonUtil jsonUtil;

    public MigrationInitializer(JdbcTemplate jdbcTemplate, ActivityRepository activityRepository, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.activityRepository = activityRepository;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public void run(String... args) throws Exception {
        // 1) 마이그레이션 테스트용 시드 데이터만 적용
        // (코어 스키마는 Spring Boot의 spring.sql.init이 DataSource bean 직후에 적용한다 —
        //  이전엔 여기서 schema-mariadb.sql을 적용했지만 @Scheduled가 그보다 먼저 시작되어
        //  ~30초 동안 "Table doesn't exist" 에러 스팸이 발생했음.)
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration-test-data.sql")
        );
        populator.setContinueOnError(true);
        populator.execute(jdbcTemplate.getDataSource());
        log.info("Loaded migration test seed data (core schema is applied by spring.sql.init)");

        // 2) 워크플로우 인스턴스 생성
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, 'DataMigrationLine', 'RUNNING', NULL, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, instanceId);

        // 3) SRC_DATA에서 아직 마이그레이션 안 된 데이터 조회 후, 각 건에 대해 PENDING 생성
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT ID, CONTENT FROM SRC_DATA WHERE MIGRATED_FL = 'N' ORDER BY REG_DT ASC");
        LocalDateTime nextRetry = null; // 즉시 실행
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("ID"));
            String content = String.valueOf(row.get("CONTENT"));
            String payload = jsonUtil.toJson(Map.of("id", id, "content", content));
            activityRepository.createPending(instanceId, "MIGRATION_WRITE", payload, nextRetry);
        }

        log.info("Seeded {} pending migration activities for instance {}", rows.size(), instanceId);

        // 참고: content에 "Second"가 포함된 항목은 DataMigrationLine에서 강제로 실패를 던져
        // 백오프 재시도 로직(엔진의 TaskExecutor.fail) 동작을 확인할 수 있다.
    }
}

