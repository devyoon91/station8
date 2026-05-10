package com.station8.engine.core;

import com.station8.engine.dialect.DbDialect;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.JdbcActivityRepository;
import com.station8.engine.repository.JdbcLineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #138 — SlaPoller 통합 테스트.
 *
 * <p>시나리오:</p>
 * <ul>
 *   <li>SLA 미설정 → 무시</li>
 *   <li>SLA 임계치 미초과 → 알림 X</li>
 *   <li>SLA 위반 + ALERT_ONLY → 알림만, instance 그대로 RUNNING</li>
 *   <li>SLA 위반 + AUTO_TERMINATE → 알림 + instance TERMINATED</li>
 *   <li>인스턴스 RUN_OPTIONS의 slaSeconds override가 정의 default보다 우선</li>
 * </ul>
 */
class SlaPollerTest {

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcActivityRepository activityRepo;
    private static JdbcLineDefinitionRepository defRepo;
    private static JdbcLineExecutor lineExecutor;
    private static SlaPoller poller;
    private static CapturingSlaNotifier capturingNotifier;
    private static final JsonUtil jsonUtil = new JsonUtil();

    private static final DbDialect H2_DIALECT = new DbDialect() {
        @Override public String limit(int limit) { return " FETCH FIRST " + limit + " ROWS ONLY"; }
        @Override public String offsetLimit(int offset, int limit) {
            return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }
        @Override public String currentTimestamp() { return "CURRENT_TIMESTAMP"; }
    };

    @BeforeAll
    static void setup() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:sla_poller_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator pop = new ResourceDatabasePopulator();
        pop.addScript(new ClassPathResource("sql/schema-h2.sql"));
        pop.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        activityRepo = new JdbcActivityRepository(jdbcTemplate, H2_DIALECT);
        defRepo = new JdbcLineDefinitionRepository(jdbcTemplate, H2_DIALECT);
        lineExecutor = new JdbcLineExecutor(jdbcTemplate, activityRepo, jsonUtil);
        capturingNotifier = new CapturingSlaNotifier();
        poller = new SlaPoller(activityRepo, defRepo, capturingNotifier, lineExecutor, jsonUtil,
                new RunOptionsCodec(jsonUtil));
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
        capturingNotifier.violations.clear();
    }

    @Test
    void noSlaConfigured_pollerSkipsInstance() {
        seedDefinition("FlowNoSla", null, null);
        String inst = seedRunningInstance("FlowNoSla", LocalDateTime.now().minusDays(7), null);

        poller.pollSlaViolations();

        assertThat(capturingNotifier.violations).isEmpty();
        assertThat(activityRepo.findInstanceById(inst).statusSt()).isEqualTo("RUNNING");
    }

    @Test
    void notExceededYet_noViolation() {
        seedDefinition("FlowQuick", 3600L, "ALERT_ONLY");
        String inst = seedRunningInstance("FlowQuick", LocalDateTime.now().minusSeconds(60), null);

        poller.pollSlaViolations();

        assertThat(capturingNotifier.violations).isEmpty();
        assertThat(activityRepo.findInstanceById(inst).statusSt()).isEqualTo("RUNNING");
    }

    @Test
    void exceededWithAlertOnly_notifiesButKeepsRunning() {
        seedDefinition("FlowAlert", 60L, "ALERT_ONLY");
        String inst = seedRunningInstance("FlowAlert", LocalDateTime.now().minusSeconds(120), null);

        poller.pollSlaViolations();

        assertThat(capturingNotifier.violations).hasSize(1);
        SlaViolation v = capturingNotifier.violations.get(0);
        assertThat(v.instanceId()).isEqualTo(inst);
        assertThat(v.action()).isEqualTo(SlaAction.ALERT_ONLY);
        assertThat(v.elapsedSeconds()).isGreaterThanOrEqualTo(60L);
        // Instance는 그대로 RUNNING
        assertThat(activityRepo.findInstanceById(inst).statusSt()).isEqualTo("RUNNING");
    }

    @Test
    void exceededWithAutoTerminate_notifiesAndTerminates() {
        seedDefinition("FlowKill", 30L, "AUTO_TERMINATE");
        String inst = seedRunningInstance("FlowKill", LocalDateTime.now().minusSeconds(60), null);

        poller.pollSlaViolations();

        assertThat(capturingNotifier.violations).hasSize(1);
        assertThat(capturingNotifier.violations.get(0).action()).isEqualTo(SlaAction.AUTO_TERMINATE);

        LineInstance updated = activityRepo.findInstanceById(inst);
        assertThat(updated.statusSt()).isEqualTo("TERMINATED");
        assertThat(updated.outputData()).contains("SLA violation").contains("auto-terminate");
    }

    @Test
    void instanceRunOptionsOverride_winsOverDefinitionDefault() {
        // 정의는 SLA 1시간 default, 인스턴스는 30초 override → 짧은 쪽 적용
        seedDefinition("FlowOverride", 3600L, "ALERT_ONLY");
        String runOpts = "{\"slaSeconds\":30,\"slaAction\":\"AUTO_TERMINATE\"}";
        String inst = seedRunningInstance("FlowOverride", LocalDateTime.now().minusSeconds(60), runOpts);

        poller.pollSlaViolations();

        assertThat(capturingNotifier.violations).hasSize(1);
        assertThat(capturingNotifier.violations.get(0).thresholdSeconds()).isEqualTo(30L);
        assertThat(capturingNotifier.violations.get(0).action()).isEqualTo(SlaAction.AUTO_TERMINATE);
        assertThat(activityRepo.findInstanceById(inst).statusSt()).isEqualTo("TERMINATED");
    }

    @Test
    void terminatedInstance_skipsSilently() {
        // 이미 종료된 인스턴스는 폴러가 발견 안 함 (RUNNING만 query)
        seedDefinition("FlowDone", 30L, "AUTO_TERMINATE");
        String inst = "inst-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, USE_FL, VIEW_FL, DEL_FL, START_DT, END_DT, REG_DT)
            VALUES (?, ?, 'COMPLETED', 'Y', 'Y', 'N', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, inst, "FlowDone", java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(60)));

        poller.pollSlaViolations();

        assertThat(capturingNotifier.violations).isEmpty();
    }

    // ---- 헬퍼 ----

    private String seedDefinition(String name, Long slaSeconds, String slaAction) {
        String defId = "def-" + UUID.randomUUID();
        defRepo.insertDefinition(new LineDefinition(
                defId, name, null, 1, "Y",
                slaSeconds, slaAction, null,
                "Y", "Y", "N",
                null, "test", null, null));
        return defId;
    }

    private String seedRunningInstance(String workflowName, LocalDateTime startDt, String runOptions) {
        String id = "inst-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO U_LINE_INSTANCE
              (ID, WORKFLOW_NAME, STATUS_ST, RUN_OPTIONS, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', ?, CURRENT_TIMESTAMP)
            """, id, workflowName, runOptions, java.sql.Timestamp.valueOf(startDt));
        return id;
    }

    /** Notifier mock — 알림 이력 capture. */
    static class CapturingSlaNotifier implements SlaNotifier {
        final List<SlaViolation> violations = new ArrayList<>();

        @Override
        public void notify(SlaViolation violation, String overrideUrl) {
            violations.add(violation);
        }
    }
}
