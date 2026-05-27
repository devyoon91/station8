package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.core.ConcurrencyPolicy;
import com.station8.engine.core.RunOptions;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #141 — 동시 실행 정책 (CONCURRENT / SKIP_IF_RUNNING) 통합 테스트.
 *
 * <p>instance 상태를 직접 {@code UPDATE}해 동시성 게이트를 검증한다. 백그라운드 워커 폴러는
 * 테스트 JVM에서 비활성(build.gradle systemProperty, #353)이라 간섭하지 않는다.</p>
 */
@SpringBootTest(classes = Application.class)
class ConcurrencyPolicyTest {

    @Autowired LineDefinitionService service;
    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"));
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION_ACL");
        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_SCHEDULE");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    @Test
    void concurrent_default_allowsSecondInstance() {
        // CONCURRENT (default — null) — 첫/둘째 둘 다 RUNNING
        String defId = createDefinition("ConcurrentFlow", null);

        RunResult first = service.runDefinitionWithResult(defId, null, null);
        RunResult second = service.runDefinitionWithResult(defId, null, null);

        assertThat(first.skipped()).isFalse();
        assertThat(second.skipped()).isFalse();
        assertThat(first.instanceId()).isNotEqualTo(second.instanceId());
    }

    @Test
    void skipIfRunning_secondCallSkipped_whenFirstIsRunning() {
        String defId = createDefinition("SkipFlow", "SKIP_IF_RUNNING");

        RunResult first = service.runDefinitionWithResult(defId, null, null);
        RunResult second = service.runDefinitionWithResult(defId, null, null);

        assertThat(first.skipped()).isFalse();
        assertThat(first.instanceId()).isNotNull();
        assertThat(second.skipped()).isTrue();
        assertThat(second.instanceId()).isNull();
        assertThat(second.conflictingInstanceId()).isEqualTo(first.instanceId());
        assertThat(second.reason()).contains("SKIP_IF_RUNNING");
    }

    @Test
    void skipIfRunning_secondCallAllowed_afterFirstCompletes() {
        String defId = createDefinition("CompletedThenNew", "SKIP_IF_RUNNING");

        RunResult first = service.runDefinitionWithResult(defId, null, null);
        // 첫 인스턴스를 COMPLETED로 전이 (워커 시뮬레이션)
        jdbcTemplate.update(
                "UPDATE U_LINE_INSTANCE SET STATUS_ST = 'COMPLETED' WHERE ID = ?",
                first.instanceId());

        RunResult second = service.runDefinitionWithResult(defId, null, null);
        assertThat(second.skipped()).isFalse();
        assertThat(second.instanceId()).isNotNull();
    }

    @Test
    void skipIfRunning_pausedInstance_alsoCountsAsActive() {
        // PAUSED 인스턴스도 활성으로 간주 — 새 인스턴스 시작 거부
        String defId = createDefinition("PausedActive", "SKIP_IF_RUNNING");

        RunResult first = service.runDefinitionWithResult(defId, null, null);
        jdbcTemplate.update(
                "UPDATE U_LINE_INSTANCE SET STATUS_ST = 'PAUSED' WHERE ID = ?",
                first.instanceId());

        RunResult second = service.runDefinitionWithResult(defId, null, null);
        assertThat(second.skipped()).isTrue();
        assertThat(second.conflictingInstanceId()).isEqualTo(first.instanceId());
    }

    @Test
    void skipIfRunning_failedInstance_doesNotBlock() {
        // FAILED 인스턴스는 활성 X — 새 인스턴스 OK
        String defId = createDefinition("FailedNotBlock", "SKIP_IF_RUNNING");

        RunResult first = service.runDefinitionWithResult(defId, null, null);
        jdbcTemplate.update(
                "UPDATE U_LINE_INSTANCE SET STATUS_ST = 'FAILED' WHERE ID = ?",
                first.instanceId());

        RunResult second = service.runDefinitionWithResult(defId, null, null);
        assertThat(second.skipped()).isFalse();
    }

    @Test
    void runDefinition_legacyMethod_throwsOnSkip() {
        // 후방 호환 — 기존 String 반환 메서드는 SKIP 시 IllegalStateException
        String defId = createDefinition("LegacyMethod", "SKIP_IF_RUNNING");

        service.runDefinition(defId, null);  // 첫 호출 OK
        assertThatThrownBy(() -> service.runDefinition(defId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SKIP");
    }

    @Test
    void skipIfRunning_overriddenToConcurrent_atInstanceLevel_allowsSecondStart() {
        // #165 — def=SKIP_IF_RUNNING + instance override=CONCURRENT → 두 번째도 시작 (D1=A)
        String defId = createDefinition("SkipOverrideToConcurrent", "SKIP_IF_RUNNING");

        RunResult first = service.runDefinitionWithResult(defId, null, null);
        assertThat(first.skipped()).isFalse();

        RunOptions override = new RunOptions(
                RunOptions.OnFailure.CONTINUE, Map.of(), null, null, null,
                ConcurrencyPolicy.CONCURRENT);
        RunResult second = service.runDefinitionWithResult(defId, null, override);

        assertThat(second.skipped()).as("override=CONCURRENT는 def=SKIP_IF_RUNNING 무시").isFalse();
        assertThat(second.instanceId()).isNotNull().isNotEqualTo(first.instanceId());
    }

    @Test
    void concurrent_overriddenToSkipIfRunning_atInstanceLevel_blocksSecondStart() {
        // #165 — def=CONCURRENT + instance override=SKIP_IF_RUNNING → 두 번째 SKIP (역방향 override)
        String defId = createDefinition("ConcurrentOverrideToSkip", null);

        RunResult first = service.runDefinitionWithResult(defId, null, null);
        assertThat(first.skipped()).isFalse();

        RunOptions override = new RunOptions(
                RunOptions.OnFailure.CONTINUE, Map.of(), null, null, null,
                ConcurrencyPolicy.SKIP_IF_RUNNING);
        RunResult second = service.runDefinitionWithResult(defId, null, override);

        assertThat(second.skipped()).as("override=SKIP_IF_RUNNING은 def=CONCURRENT 무시").isTrue();
        assertThat(second.conflictingInstanceId()).isEqualTo(first.instanceId());
    }

    @Test
    void overriddenPolicy_persistedInRunOptionsClob() {
        // override 정책이 U_LINE_INSTANCE.RUN_OPTIONS에 직렬화되는지 확인
        String defId = createDefinition("OverridePersisted", null);

        RunOptions override = new RunOptions(
                RunOptions.OnFailure.CONTINUE, Map.of(), null, null, null,
                ConcurrencyPolicy.SKIP_IF_RUNNING);
        RunResult result = service.runDefinitionWithResult(defId, null, override);
        assertThat(result.skipped()).isFalse();

        LineInstance inst = activityRepository.findInstanceById(result.instanceId());
        assertThat(inst.runOptions()).isNotNull().contains("SKIP_IF_RUNNING");
    }

    private String createDefinition(String name, String concurrencyPolicy) {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm(name)
                .description("test")
                .concurrencyPolicy(concurrencyPolicy)
                .nodes(List.of(new DagDefinitionRequest.NodeDef("c-1", "A", "MIGRATION_WRITE", null, 0, 0, null)))
                .edges(List.of())
                .build();
        return service.createDefinition(req);
    }
}
