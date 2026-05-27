package com.station8.app.demo;

import com.station8.app.definition.DagDefinitionRequest;
import com.station8.app.definition.LineDefinitionService;
import com.station8.app.schedule.ScheduleService;
import com.station8.engine.crypto.CredentialCrypto;
import com.station8.engine.entity.Credential;
import com.station8.engine.entity.LineSchedule;
import com.station8.engine.repository.CredentialRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.repository.LineScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 *   <li>{@code DemoFileInbound} — {@code file.read} → {@code MIGRATION_WRITE} (cron 없음, 수동 run-now)</li>
 *   <li>{@code DemoFileOutbound} — {@code NOOP} → {@code file.write} (cron 없음, 수동 run-now)</li>
 *   <li>{@code DemoLlmAgent} — 단일 {@code llm.agent} + {@code get_weather} 도구 (#345, 수동 run-now)</li>
 *   <li>{@code demo-llm} credential — {@link DemoChatController} endpoint를 baseUrl로</li>
 *   <li>{@code ${java.io.tmpdir}/station8-demo/inbox} 디렉토리 + {@code order.json} 샘플 파일</li>
 *   <li>{@code ${java.io.tmpdir}/station8-demo/outbox} 디렉토리</li>
 * </ul>
 *
 * <p>HTTP 데모는 자체 endpoint {@link DemoEchoController}, 파일 데모는 OS tempdir 안 inbox/outbox를
 * 부른다 — 둘 다 외부 인프라 의존 0. demo 프로파일의 {@code station8.http.allowlist} +
 * {@code station8.file.local.allowed-roots} 가 NetworkPolicy / FilePathPolicy 기본 차단을 우회한다.</p>
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

    private static final String DEMO_FILE_INBOUND_NM = "DemoFileInbound";
    private static final String DEMO_FILE_OUTBOUND_NM = "DemoFileOutbound";
    /** inbox에 자동 시드되는 샘플 파일 이름. DemoFileInbound가 이걸 읽는다. */
    private static final String DEMO_INBOX_FILE = "order.json";

    private static final String DEMO_LLM_AGENT_NM = "DemoLlmAgent";
    /** agent 데모 credential 이름 — DemoChatController endpoint를 baseUrl로. */
    private static final String DEMO_LLM_CREDENTIAL = "demo-llm";
    private static final String DEMO_LLM_BASE_URL = "http://localhost:8080/api/demo/llm/v1";

    private final LineDefinitionService definitionService;
    private final ScheduleService scheduleService;
    private final LineDefinitionRepository definitionRepository;
    private final LineScheduleRepository scheduleRepository;
    private final CredentialRepository credentialRepository;
    private final CredentialCrypto credentialCrypto;

    /**
     * application-demo.properties의 {@code station8.file.local.allowed-roots}는 csv —
     * inbox / outbox 두 path가 들어있다. 여기서는 첫 두 항목을 inbox / outbox로 가정해 시드.
     */
    @Value("${station8.file.local.allowed-roots:}")
    private String fileAllowedRoots;

    public DemoSeedRunner(LineDefinitionService definitionService,
                          ScheduleService scheduleService,
                          LineDefinitionRepository definitionRepository,
                          LineScheduleRepository scheduleRepository,
                          CredentialRepository credentialRepository,
                          CredentialCrypto credentialCrypto) {
        this.definitionService = definitionService;
        this.scheduleService = scheduleService;
        this.definitionRepository = definitionRepository;
        this.scheduleRepository = scheduleRepository;
        this.credentialRepository = credentialRepository;
        this.credentialCrypto = credentialCrypto;
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

        // 파일 데모는 디렉토리 + 샘플 파일 시드도 같이.
        DemoFilePaths paths = resolveFilePaths();
        if (paths != null) {
            bootstrapDemoFiles(paths);
            seedIfMissing(DEMO_FILE_INBOUND_NM, () -> seedFileInbound(paths));
            seedIfMissing(DEMO_FILE_OUTBOUND_NM, () -> seedFileOutbound(paths));
        } else {
            log.warn("[DemoSeed] station8.file.local.allowed-roots 미설정 — 파일 데모 라인 skip");
        }

        // AI agent 데모 (#345) — DemoChatController(자체 LLM endpoint) + get_weather 도구.
        seedDemoLlmCredentialIfMissing();
        seedIfMissing(DEMO_LLM_AGENT_NM, this::seedLlmAgent);
    }

    /**
     * agent 데모용 credential 시드 — DemoChatController endpoint를 baseUrl로. 이미 있으면 skip.
     * STATION8_CREDENTIAL_KEY 미설정 시 암호화 실패 → WARN 후 skip (agent 데모만 비활성, 부팅 영향 X).
     */
    private void seedDemoLlmCredentialIfMissing() {
        if (credentialRepository.findByName(DEMO_LLM_CREDENTIAL) != null) {
            log.info("[DemoSeed] credential '{}' already exists, skipping", DEMO_LLM_CREDENTIAL);
            return;
        }
        try {
            String schemaJson = "{\"baseUrl\":\"" + DEMO_LLM_BASE_URL + "\"}";
            credentialRepository.insert(new Credential(
                    java.util.UUID.randomUUID().toString(), DEMO_LLM_CREDENTIAL, "openai_compatible",
                    credentialCrypto.encrypt("demo-key"), schemaJson, "N", null, "demo-seed", null, null));
            log.info("[DemoSeed] credential '{}' 시드 완료 (baseUrl={})", DEMO_LLM_CREDENTIAL, DEMO_LLM_BASE_URL);
        } catch (Exception ex) {
            log.warn("[DemoSeed] credential '{}' 시드 실패 — STATION8_CREDENTIAL_KEY 확인. agent 데모 skip",
                    DEMO_LLM_CREDENTIAL, ex);
        }
    }

    /**
     * 데모 5 — AI agent 루프. 단일 {@code llm.agent} 노드, {@code get_weather} 도구를 allowlist로.
     * DemoChatController가 "도구 호출 요청 → (결과 받은 뒤) 최종 답변"을 흉내내 외부 LLM 없이 동작.
     */
    private String seedLlmAgent() {
        String agentInput = "{"
                + "\"credentialId\":\"" + DEMO_LLM_CREDENTIAL + "\","
                + "\"model\":\"demo-model\","
                + "\"prompt\":\"What is the weather in Seoul?\","
                + "\"tools\":[{"
                +   "\"name\":\"get_weather\","
                +   "\"description\":\"도시의 현재 날씨 조회\","
                +   "\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
                + "}]"
                + "}";
        return definitionService.createDefinition(DagDefinitionRequest.builder()
                .definitionNm(DEMO_LLM_AGENT_NM)
                .description("데모: AI agent 루프 — LLM이 get_weather 도구 호출 후 답변 (수동 run-now). DemoChatController 사용")
                .nodes(List.of(new DagDefinitionRequest.NodeDef(
                        "n-agent", "Agent", "llm.agent", agentInput, 150, 100, null)))
                .edges(List.of())
                .build());
    }

    /** allowed-roots csv를 inbox/outbox로 해석. 항목이 부족하면 null. */
    private DemoFilePaths resolveFilePaths() {
        if (fileAllowedRoots == null || fileAllowedRoots.isBlank()) {
            return null;
        }
        String[] parts = fileAllowedRoots.split(",");
        if (parts.length < 2) {
            log.warn("[DemoSeed] allowed-roots 항목 부족 ({}) — 파일 데모 skip", parts.length);
            return null;
        }
        return new DemoFilePaths(
                Paths.get(parts[0].trim()),
                Paths.get(parts[1].trim()));
    }

    /** inbox / outbox 디렉토리 + 샘플 파일 1건을 부팅 시 자동 생성. 이미 있으면 그대로. */
    private void bootstrapDemoFiles(DemoFilePaths paths) {
        try {
            Files.createDirectories(paths.inbox);
            Files.createDirectories(paths.outbox);
            Path sample = paths.inbox.resolve(DEMO_INBOX_FILE);
            if (!Files.exists(sample)) {
                String body = "{\"orderId\":\"demo-1\",\"title\":\"Sample inbox order\"}";
                Files.writeString(sample, body, StandardCharsets.UTF_8);
                log.info("[DemoSeed] inbox 샘플 파일 생성: {}", sample);
            }
        } catch (IOException ex) {
            log.warn("[DemoSeed] inbox/outbox bootstrap 실패 — 파일 데모 동작 안 할 수 있음", ex);
        }
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

    /**
     * 데모 3 — inbox JSON 파일을 읽어 DB insert. file.read → MIGRATION_WRITE.
     *
     * <p>{@code order.json}이 {@code {"orderId":"demo-1", "title":"..."}}이라
     * 다음 노드는 {@code $prev.json.content.orderId} / {@code .title}로 끌어와 매핑.</p>
     */
    private String seedFileInbound(DemoFilePaths paths) {
        Path samplePath = paths.inbox.resolve(DEMO_INBOX_FILE);
        String readInput = "{"
                + "\"uri\":\"" + uriString(samplePath) + "\","
                + "\"format\":\"json\""
                + "}";
        // file.read 응답: { uri, format, sizeBytes, content: {...} }
        // content.orderId / title을 꺼냄.
        String migrateInput = "{"
                + "\"id\":\"file-{{ $prev.json.content.orderId }}\","
                + "\"content\":\"{{ $prev.json.content.title }}\""
                + "}";

        return definitionService.createDefinition(DagDefinitionRequest.builder()
                .definitionNm(DEMO_FILE_INBOUND_NM)
                .description("데모: inbox 파일 → DB insert (수동 run-now). 샘플 파일: " + DEMO_INBOX_FILE)
                .nodes(List.of(
                        new DagDefinitionRequest.NodeDef(
                                "n-read", "Read", "file.read",
                                readInput, 100, 100, null),
                        new DagDefinitionRequest.NodeDef(
                                "n-write", "Write", "MIGRATION_WRITE",
                                migrateInput, 350, 100, null)))
                .edges(List.of(new DagDefinitionRequest.EdgeDef(
                        "e-read-write", "n-read", "n-write", null)))
                .build());
    }

    /**
     * 데모 4 — payload 준비 후 outbox에 결과 파일 write. NOOP → file.write.
     *
     * <p>출력 파일 이름은 {@code $ctx.run.id}로 인스턴스마다 unique 하게.</p>
     */
    private String seedFileOutbound(DemoFilePaths paths) {
        String noopInput = "{"
                + "\"user\":\"{{ $ctx.input.user }}\","
                + "\"line\":\"{{ $ctx.line.name }}\""
                + "}";
        // outbox path는 outbox/result-{run.id}.json — 매 인스턴스 unique
        String outPath = paths.outbox.toString().replace("\\", "/")
                + "/result-{{ $ctx.run.id }}.json";
        String writeInput = "{"
                + "\"uri\":\"file://" + outPath + "\","
                + "\"format\":\"json\","
                + "\"content\":{"
                +     "\"user\":\"{{ $prev.json.user }}\","
                +     "\"from\":\"{{ $prev.json.line }}\","
                +     "\"writtenBy\":\"DemoFileOutbound\""
                + "}"
                + "}";

        return definitionService.createDefinition(DagDefinitionRequest.builder()
                .definitionNm(DEMO_FILE_OUTBOUND_NM)
                .description("데모: payload 준비 후 outbox에 JSON write — outbox path는 인스턴스마다 unique")
                .nodes(List.of(
                        new DagDefinitionRequest.NodeDef(
                                "n-prep", "Prep", "NOOP",
                                noopInput, 100, 100, null),
                        new DagDefinitionRequest.NodeDef(
                                "n-write", "Write", "file.write",
                                writeInput, 350, 100, null)))
                .edges(List.of(new DagDefinitionRequest.EdgeDef(
                        "e-prep-write", "n-prep", "n-write", null)))
                .build());
    }

    /**
     * Path를 file:// URI string으로. Windows path의 backslash는 forward로 정규화 (URI 표준).
     * Paths.toUri()는 자동으로 처리하지만 인스턴스 인터폴레이션 path는 수동 구성이라 여기서.
     */
    private static String uriString(Path path) {
        return path.toUri().toString();
    }

    /** inbox / outbox 두 path를 묶음. allowed-roots 첫 두 항목을 받아 채움. */
    private record DemoFilePaths(Path inbox, Path outbox) {}
}
