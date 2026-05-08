package com.station8.app.schedule;

import com.station8.app.Application;
import com.station8.app.definition.DagDefinitionRequest;
import com.station8.app.definition.LineDefinitionService;
import com.station8.engine.entity.LineSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스케줄 서비스/컨트롤러 동작 통합 테스트.
 */
@SpringBootTest(classes = Application.class)
class ScheduleApiTest {

    @Autowired ScheduleService scheduleService;
    @Autowired LineDefinitionService definitionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private String defId;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"),
                new ClassPathResource("sql/migration-test-data.sql")
        );
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM H_WF_DLQ");
        jdbcTemplate.execute("DELETE FROM H_WF_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_WF_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_WF_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_WF_EDGE");
        jdbcTemplate.execute("DELETE FROM U_WF_NODE");
        jdbcTemplate.execute("DELETE FROM U_WF_DEFINITION");

        // 단일 노드 정의 — 검증 통과
        defId = definitionService.createDefinition(new DagDefinitionRequest(
                "ScheduleTestFlow", null,
                List.of(new DagDefinitionRequest.NodeDef(
                        "n-only", "Only", "MIGRATION_WRITE", null, 0, 0)),
                List.of()
        ));
    }

    @Test
    void create_schedule_with_valid_cron_sets_nextRunDt_in_future() {
        String id = scheduleService.create(defId, "0 */5 * * * *", null);
        LineSchedule s = scheduleService.findById(id);
        assertNotNull(s.nextRunDt());
        assertTrue(s.nextRunDt().isAfter(LocalDateTime.now()), "nextRunDt 미래여야 함");
        assertEquals("N", s.pausedFl());
    }

    @Test
    void create_with_invalid_cron_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> scheduleService.create(defId, "not a cron", null));
        assertTrue(ex.getMessage().contains("cron"), ex.getMessage());
    }

    @Test
    void create_with_unknown_definition_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> scheduleService.create("non-existent-def", "0 0 * * * *", null));
        assertTrue(ex.getMessage().contains("정의"), ex.getMessage());
    }

    @Test
    void pause_resume_toggles_flag() {
        String id = scheduleService.create(defId, "0 0 * * * *", null);

        scheduleService.pause(id);
        assertEquals("Y", scheduleService.findById(id).pausedFl());

        scheduleService.resume(id);
        assertEquals("N", scheduleService.findById(id).pausedFl());
    }

    @Test
    void runNow_creates_instance_immediately() {
        String id = scheduleService.create(defId, "0 0 * * * *", null);
        String instanceId = scheduleService.runNow(id);
        assertNotNull(instanceId);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM U_WF_INSTANCE WHERE ID = ?", Integer.class, instanceId);
        assertEquals(1, count, "즉시 실행은 인스턴스 1개를 생성해야 함");
    }

    @Test
    void updateCron_changes_nextRun() {
        String id = scheduleService.create(defId, "0 0 0 * * *", null);  // 매일 자정
        LocalDateTime before = scheduleService.findById(id).nextRunDt();

        scheduleService.updateCron(id, "0 */5 * * * *");  // 5분마다
        LocalDateTime after = scheduleService.findById(id).nextRunDt();

        assertNotEquals(before, after);
        assertTrue(after.isBefore(before), "5분 cron의 next는 매일 자정보다 앞이어야 함");
    }

    @Test
    void delete_then_findById_throws() {
        String id = scheduleService.create(defId, "0 0 * * * *", null);
        scheduleService.delete(id);
        assertThrows(IllegalArgumentException.class, () -> scheduleService.findById(id));
    }

    @Test
    void listAll_excludes_deleted() {
        String id1 = scheduleService.create(defId, "0 0 * * * *", null);
        String id2 = scheduleService.create(defId, "0 30 * * * *", null);
        scheduleService.delete(id1);

        List<LineSchedule> all = scheduleService.listAll();
        assertEquals(1, all.size());
        assertEquals(id2, all.get(0).id());
    }
}
