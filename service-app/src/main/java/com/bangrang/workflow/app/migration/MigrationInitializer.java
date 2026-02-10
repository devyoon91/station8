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
 * ?곕え??珥덇린??而댄룷?뚰듃: ?뚯뒪???뚯씠釉??곗씠???앹꽦 ?? 留덉씠洹몃젅?댁뀡 ?묒뾽??PENDING?쇰줈 ?깅줉?쒕떎.
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
        // 1) ?뚯뒪???ㅽ궎留??곗씠??濡쒕뱶
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-mariadb.sql"),
                new ClassPathResource("sql/migration-test-data.sql")
        );
        populator.setContinueOnError(true);
        populator.execute(jdbcTemplate.getDataSource());
        log.info("Loaded core schema and migration test seed data");

        // 2) ?뚰겕?뚮줈???몄뒪?댁뒪 ?앹꽦
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, 'DataMigrationWorkflow', 'RUNNING', NULL, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, instanceId);

        // 3) SRC_DATA?먯꽌 ?꾩쭅 留덉씠洹몃젅?댁뀡 ?????곗씠??議고쉶 ?? 媛?嫄댁뿉 ???PENDING ?앹꽦
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT ID, CONTENT FROM SRC_DATA WHERE MIGRATED_FL = 'N' ORDER BY REG_DT ASC");
        LocalDateTime nextRetry = null; // 利됱떆 ?ㅽ뻾
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("ID"));
            String content = String.valueOf(row.get("CONTENT"));
            String payload = jsonUtil.toJson(Map.of("id", id, "content", content));
            activityRepository.createPending(instanceId, "MIGRATION_WRITE", payload, nextRetry);
        }

        log.info("Seeded {} pending migration activities for instance {}", rows.size(), instanceId);

        // 李멸퀬: content??"Second"媛 ?ы븿????ぉ? DataMigrationWorkflow?먯꽌 媛뺤젣濡??ㅽ뙣瑜??섏졇
        // 諛깆삤???ъ떆??濡쒖쭅(?붿쭊??TaskExecutor.fail) ?숈옉???뺤씤?????덈떎.
    }
}

