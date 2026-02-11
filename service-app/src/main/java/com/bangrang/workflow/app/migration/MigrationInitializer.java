package com.bangrang.workflow.app.migration;

import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.util.JsonUtil;
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
 */
@Component
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
        // 1) 테스트 스키마/데이터 로드
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-mariadb.sql"),
                new ClassPathResource("sql/migration-test-data.sql")
        );
        populator.setContinueOnError(true);
        populator.execute(jdbcTemplate.getDataSource());
        log.info("Loaded core schema and migration test seed data");

        // 2) 워크플로우 인스턴스 생성
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, 'DataMigrationWorkflow', 'RUNNING', NULL, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, instanceId);

        // 3) SRC_DATA에서 아직 마이그레이션 안 된 데이터 조회 후, 각 건에 대해 PENDING 생성
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT ID, CONTENT FROM SRC_DATA WHERE MIGRATED_FL = 'N' ORDER BY REG_DT ASC");
        LocalDateTime nextRetry = null; // 利됱떆 ?ㅽ뻾
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("ID"));
            String content = String.valueOf(row.get("CONTENT"));
            String payload = jsonUtil.toJson(Map.of("id", id, "content", content));
            activityRepository.createPending(instanceId, "MIGRATION_WRITE", payload, nextRetry);
        }

        log.info("Seeded {} pending migration activities for instance {}", rows.size(), instanceId);

        // 참고: content에 "Second"가 포함된 항목은 DataMigrationWorkflow에서 강제로 실패를 던져
        // 백오프 재시도 로직(엔진의 TaskExecutor.fail) 동작을 확인할 수 있다.
    }
}

