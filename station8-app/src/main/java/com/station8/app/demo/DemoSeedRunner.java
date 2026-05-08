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

/**
 * 데모용 자동 시드 — ``demo`` 프로파일 활성 시 부팅 직후 한 번 동작.
 *
 * 적재 항목:
 * <ul>
 *   <li>DAG 정의 1개: {@code DemoMigrationFlow} (단일 노드 ``MIGRATION_WRITE``)</li>
 *   <li>스케줄 1개: 위 정의를 5분마다 정기 실행</li>
 * </ul>
 *
 * 멱등성: 같은 이름의 정의가 이미 존재하면 시드 전체를 skip한다 (재기동 시 중복 방지).
 *
 * Order는 {@code MigrationInitializer}(SRC_DATA 시드, 기본 Order)보다 뒤에 동작하도록 명시.
 * MigrationInitializer는 schema 적용 + SRC_DATA 시드까지 담당. 본 Runner는 워크플로우 정의/스케줄만.
 */
@Component
@Profile("demo")
@Order(100)
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    private static final String DEMO_DEFINITION_NM = "DemoMigrationFlow";
    private static final String DEMO_CRON = "0 */5 * * * *"; // 매 5분 0초

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
        // 멱등성 가드 — 같은 이름의 정의가 이미 있으면 시드 전체 skip
        if (definitionRepository.findMaxVersionByName(DEMO_DEFINITION_NM) > 0) {
            log.info("[DemoSeed] '{}' already exists, skipping demo seed", DEMO_DEFINITION_NM);
            return;
        }

        try {
            String defId = definitionService.createDefinition(new DagDefinitionRequest(
                    DEMO_DEFINITION_NM,
                    "데모: MigrationInitializer가 시드한 SRC_DATA에서 한 건씩 마이그레이션 (5분마다)",
                    List.of(new DagDefinitionRequest.NodeDef(
                            "demo-node-1", "Migrate", "MIGRATION_WRITE",
                            // 정적 입력 (실제 폴러는 SRC_DATA에서 PENDING을 끌고 가서 처리)
                            "{\"id\":\"demo-1\",\"content\":\"Auto seeded data\"}",
                            150, 100)),
                    List.of()
            ));
            log.info("[DemoSeed] DAG 정의 등록: id={}, nm={}", defId, DEMO_DEFINITION_NM);

            // 같은 정의가 다른 스케줄로 이미 등록돼 있는지도 확인
            List<LineSchedule> all = scheduleRepository.findAll();
            boolean already = all.stream().anyMatch(s -> defId.equals(s.definitionId()));
            if (!already) {
                String schId = scheduleService.create(defId, DEMO_CRON, null);
                log.info("[DemoSeed] 스케줄 등록: id={}, cron='{}'", schId, DEMO_CRON);
            }
        } catch (Exception e) {
            // 시드 실패는 앱 부팅을 막지 않음 (운영자가 수동 등록 가능)
            log.warn("[DemoSeed] 시드 실패 — 앱 부팅은 계속됨", e);
        }
    }
}
