package com.station8.app.definition;

import com.station8.app.Application;
import com.station8.engine.core.RunOptions;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #134 — {@link LineDefinitionService#runDefinition(String, String, RunOptions)} 라운드트립.
 *
 * <p>검증:</p>
 * <ul>
 *   <li>옵션 미지정(default) → {@code RUN_OPTIONS} 컬럼은 NULL (후방 호환)</li>
 *   <li>{@code onFailure=ABORT} → JSON 직렬화되어 컬럼에 저장</li>
 *   <li>{@code runtimeParams} 비어있지 않으면 JSON에 포함</li>
 *   <li>{@code notificationWebhookUrl} 비어있지 않으면 JSON에 포함</li>
 *   <li>저장된 JSON을 다시 {@link RunOptions#parse} 했을 때 원본과 동일</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
class RunOptionsApiTest {

    @Autowired LineDefinitionService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ActivityRepository activityRepository;
    @Autowired JsonUtil jsonUtil;

    @BeforeEach
    void setup() {
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator(
                new ClassPathResource("sql/schema-h2.sql"),
                new ClassPathResource("sql/migration-test-data.sql")
        );
        pop.setContinueOnError(true);
        pop.execute(jdbcTemplate.getDataSource());

        jdbcTemplate.execute("DELETE FROM H_LINE_DLQ");
        jdbcTemplate.execute("DELETE FROM H_LINE_ACTIVITY_EXECUTION");
        jdbcTemplate.execute("DELETE FROM U_LINE_INSTANCE");
        jdbcTemplate.execute("DELETE FROM U_LINE_TRACK");
        jdbcTemplate.execute("DELETE FROM U_LINE_STATION");
        jdbcTemplate.execute("DELETE FROM U_LINE_DEFINITION");
    }

    private String createSimpleDefinition(String name) {
        DagDefinitionRequest req = new DagDefinitionRequest(
                name, null,
                List.of(new DagDefinitionRequest.NodeDef("n-1", "A", "MIGRATION_WRITE",
                        jsonUtil.toJson(Map.of("id", "1", "content", "noop")), 0, 0, null)),
                List.of()
        );
        return service.createDefinition(req);
    }

    @Test
    void runDefinition_withDefaults_storesNullRunOptions() {
        String defId = createSimpleDefinition("DefaultOptsFlow");

        // 후방 호환 — 옵션 없는 시그니처는 default를 사용해야 함
        String instanceId = service.runDefinition(defId, null);

        LineInstance inst = activityRepository.findInstanceById(instanceId);
        assertThat(inst).isNotNull();
        assertThat(inst.runOptions()).as("default 옵션은 RUN_OPTIONS 컬럼을 NULL로 둠").isNull();
    }

    @Test
    void runDefinition_withAbortOption_storesJson() {
        String defId = createSimpleDefinition("AbortOptsFlow");

        RunOptions opts = new RunOptions(RunOptions.OnFailure.ABORT, Map.of(), null);
        String instanceId = service.runDefinition(defId, null, opts);

        LineInstance inst = activityRepository.findInstanceById(instanceId);
        assertThat(inst.runOptions()).isNotNull();
        RunOptions roundTrip = RunOptions.parse(inst.runOptions(), jsonUtil);
        assertThat(roundTrip.onFailure()).isEqualTo(RunOptions.OnFailure.ABORT);
    }

    @Test
    void runDefinition_withPauseOnFailureOption_storesJson() {
        // #148 — PAUSE_ON_FAILURE 옵션 라운드트립
        String defId = createSimpleDefinition("PauseOnFailFlow");

        RunOptions opts = new RunOptions(RunOptions.OnFailure.PAUSE_ON_FAILURE, Map.of(), null);
        String instanceId = service.runDefinition(defId, null, opts);

        LineInstance inst = activityRepository.findInstanceById(instanceId);
        assertThat(inst.runOptions()).isNotNull().contains("PAUSE_ON_FAILURE");
        RunOptions roundTrip = RunOptions.parse(inst.runOptions(), jsonUtil);
        assertThat(roundTrip.onFailure()).isEqualTo(RunOptions.OnFailure.PAUSE_ON_FAILURE);
    }

    @Test
    void runDefinition_withRuntimeParams_serializedAndRoundTrips() {
        String defId = createSimpleDefinition("ParamsOptsFlow");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("region", "KR");
        params.put("tier", "premium");
        RunOptions opts = new RunOptions(RunOptions.OnFailure.CONTINUE, params, null);
        String instanceId = service.runDefinition(defId, "{}", opts);

        LineInstance inst = activityRepository.findInstanceById(instanceId);
        assertThat(inst.runOptions()).isNotNull().contains("region", "KR", "premium");

        RunOptions roundTrip = RunOptions.parse(inst.runOptions(), jsonUtil);
        assertThat(roundTrip.runtimeParams())
                .containsEntry("region", "KR")
                .containsEntry("tier", "premium");
    }

    @Test
    void runDefinition_withWebhookOverride_serialized() {
        String defId = createSimpleDefinition("WebhookOptsFlow");

        RunOptions opts = new RunOptions(
                RunOptions.OnFailure.CONTINUE,
                Map.of(),
                "https://hooks.example.com/instance-dlq");
        String instanceId = service.runDefinition(defId, null, opts);

        LineInstance inst = activityRepository.findInstanceById(instanceId);
        assertThat(inst.runOptions()).isNotNull().contains("hooks.example.com");

        RunOptions roundTrip = RunOptions.parse(inst.runOptions(), jsonUtil);
        assertThat(roundTrip.notificationWebhookUrl()).isEqualTo("https://hooks.example.com/instance-dlq");
    }

    @Test
    void runDefinition_withNullOptions_treatedAsDefaults() {
        String defId = createSimpleDefinition("NullOptsFlow");

        // 명시적 null도 default와 같이 동작 — 컬럼 NULL
        String instanceId = service.runDefinition(defId, null, null);

        LineInstance inst = activityRepository.findInstanceById(instanceId);
        assertThat(inst.runOptions()).isNull();
    }
}
