package com.station8.app.demo;

import com.station8.app.definition.DagDefinitionRequest;
import com.station8.app.definition.LineDefinitionService;
import com.station8.app.schedule.ScheduleService;
import com.station8.engine.entity.LineSchedule;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.repository.LineScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 데모용 자동 시드 — ``demo`` 프로파일 활성 시 부팅 직후 한 번 동작.
 *
 * <p>적재 항목:</p>
 * <ul>
 *   <li>{@code DemoMigrationFlow} — 단일 역 ``MIGRATION_WRITE`` + 5분 cron</li>
 *   <li>{@code DemoHttpInbound} — {@code http.request} → {@code MIGRATION_WRITE} (cron 없음, 수동 run-now)</li>
 *   <li>{@code DemoHttpOutbound} — {@code NOOP} → {@code http.request} POST (cron 없음, 수동 run-now)</li>
 * </ul>
 *
 * <p>HTTP 데모 라인은 외부 인터넷 없이도 동작하도록 자체 endpoint {@link DemoEchoController}
 * (`/api/demo/echo/...`)를 부른다. demo 프로파일에 {@code station8.http.allowlist=localhost,127.0.0.1}이
 * 설정돼 있어 NetworkPolicy 기본 차단을 우회한다.</p>
 *
 * <p>멱등성: 라인별로 같은 이름이 이미 존재하면 그 라인만 skip. 한 라인이 실패해도 다른 라인은 계속.</p>
 *
 * <p>Order는 {@code MigrationInitializer}(SRC_DATA 시드, 기본 Order)보다 뒤에 동작하도록 명시.</p>
 */
@Component
@Profile("demo")
@Order(100)
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    private static final String DEMO_DEFINITION_NM = "DemoMigrationFlow";
    private static final String DEMO_CRON = "0 */5 * * * *"; // 매 5분 0초

    private static final String DEMO_HTTP_INBOUND_NM = "DemoHttpInbound";
    private static final String DEMO_HTTP_OUTBOUND_NM = "DemoHttpOutbound";
    /** 데모 데이터를 끌어올 자체 endpoint. demo 프로파일 한정. */
    private static final String DEMO_ECHO_BASE = "http://localhost:8080/api/demo/echo";

    private final LineDefinitionService definitionService;
    private final ScheduleService scheduleService;
    private final LineDefinitionRepository definitionRepository;
    private final LineScheduleRepository scheduleRepository;

    public DemoSeedRunner(LineDefinitionService definitionService,
                          ScheduleService scheduleService,
                          LineDefinitionRepository definitionRepository,
                          LineScheduleRepository scheduleRepository) {
        this.definitionService = definitionService;
        this.scheduleService = scheduleService;
        this.definitionRepository = definitionRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfMissing(DEMO_DEFINITION_NM, () -> {
            String defId = definitionService.createDefinition(DagDefinitionRequest.builder()
                    .definitionNm(DEMO_DEFINITION_NM)
                    .description("데모: MigrationInitializer가 시드한 SRC_DATA에서 한 건씩 마이그레이션 (5분마다)")
                    .nodes(List.of(new DagDefinitionRequest.NodeDef(
                            "demo-node-1", "Migrate", "MIGRATION_WRITE",
                            "{\"id\":\"demo-1\",\"content\":\"Auto seeded data\"}",
                            150, 100, null)))
                    .edges(List.of())
                    .build());

            // DemoMigrationFlow만 cron — HTTP 데모는 수동 run-now (외부 호출 부담 방지)
            List<LineSchedule> all = scheduleRepository.findAll();
            boolean already = all.stream().anyMatch(s -> defId.equals(s.definitionId()));
            if (!already) {
                String schId = scheduleService.create(defId, DEMO_CRON, null);
                log.info("[DemoSeed] '{}' 스케줄 등록: id={}, cron='{}'",
                        DEMO_DEFINITION_NM, schId, DEMO_CRON);
            }
            return defId;
        });

        seedIfMissing(DEMO_HTTP_INBOUND_NM, this::seedHttpInbound);
        seedIfMissing(DEMO_HTTP_OUTBOUND_NM, this::seedHttpOutbound);
    }

    /**
     * 같은 이름이 이미 있으면 skip, 없으면 supplier를 호출해 시드. supplier에서 예외 발생하면
     * WARN 로그만 — 데모 시드 실패가 부팅 자체를 막지 않게.
     */
    private void seedIfMissing(String definitionNm, Supplier<String> seeder) {
        if (definitionRepository.findMaxVersionByName(definitionNm) > 0) {
            log.info("[DemoSeed] '{}' already exists, skipping", definitionNm);
            return;
        }
        try {
            String defId = seeder.get();
            log.info("[DemoSeed] '{}' 시드 완료: id={}", definitionNm, defId);
        } catch (Exception ex) {
            log.warn("[DemoSeed] '{}' 시드 실패 — 다른 시드는 계속 진행", definitionNm, ex);
        }
    }

    /**
     * 데모 1 — HTTP 응답을 DB insert로 흘림. http.request → MIGRATION_WRITE 2노드.
     *
     * <p>1. {@code GET /api/demo/echo/post} 응답 = {id, userId, title, body}</p>
     * <p>2. 다음 노드는 응답의 title을 끌어와 MIGRATION_WRITE의 {id, content}로 매핑</p>
     */
    private String seedHttpInbound() {
        String httpInput = "{"
                + "\"method\":\"GET\","
                + "\"url\":\"" + DEMO_ECHO_BASE + "/post\""
                + "}";
        // {{ $prev.json.body.id }} — http.request 응답 객체에서 body.id 추출.
        // 표현식 평가 후 string으로 들어가 MIGRATION_WRITE 입력 JSON이 완성됨.
        String migrateInput = "{"
                + "\"id\":\"http-{{ $prev.json.body.id }}\","
                + "\"content\":\"{{ $prev.json.body.title }}\""
                + "}";

        return definitionService.createDefinition(DagDefinitionRequest.builder()
                .definitionNm(DEMO_HTTP_INBOUND_NM)
                .description("데모: http.request로 외부 API 호출 → 응답을 DB insert (수동 run-now)")
                .nodes(List.of(
                        new DagDefinitionRequest.NodeDef(
                                "n-fetch", "Fetch", "http.request",
                                httpInput, 100, 100, null),
                        new DagDefinitionRequest.NodeDef(
                                "n-write", "Write", "MIGRATION_WRITE",
                                migrateInput, 350, 100, null)))
                .edges(List.of(new DagDefinitionRequest.EdgeDef(
                        "e-fetch-write", "n-fetch", "n-write", null)))
                .build());
    }

    /**
     * 데모 2 — payload 준비 후 외부 webhook으로 POST. NOOP → http.request 2노드.
     *
     * <p>1. NOOP은 입력을 그대로 출력 — 라인 입력이 다음 노드의 $prev.json이 됨</p>
     * <p>2. http.request POST {@code /api/demo/echo/sink}는 본문을 echo 응답으로 받음 (round-trip 검증)</p>
     */
    private String seedHttpOutbound() {
        String noopInput = "{"
                + "\"user\":\"{{ $ctx.input.user }}\","
                + "\"line\":\"{{ $ctx.line.name }}\""
                + "}";
        // body는 object — http.request가 자동으로 JSON serialize + Content-Type: application/json
        String httpInput = "{"
                + "\"method\":\"POST\","
                + "\"url\":\"" + DEMO_ECHO_BASE + "/sink\","
                + "\"body\":{"
                +     "\"user\":\"{{ $prev.json.user }}\","
                +     "\"from\":\"{{ $prev.json.line }}\","
                +     "\"msg\":\"hello from demo line\""
                + "}"
                + "}";

        return definitionService.createDefinition(DagDefinitionRequest.builder()
                .definitionNm(DEMO_HTTP_OUTBOUND_NM)
                .description("데모: payload 준비 후 외부 webhook으로 POST (수동 run-now)")
                .nodes(List.of(
                        new DagDefinitionRequest.NodeDef(
                                "n-prep", "Prep", "NOOP",
                                noopInput, 100, 100, null),
                        new DagDefinitionRequest.NodeDef(
                                "n-post", "Post", "http.request",
                                httpInput, 350, 100, null)))
                .edges(List.of(new DagDefinitionRequest.EdgeDef(
                        "e-prep-post", "n-prep", "n-post", null)))
                .build());
    }
}
