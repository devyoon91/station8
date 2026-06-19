package com.station8.app.definition;

import com.station8.engine.core.ConcurrencyStrategy;
import com.station8.engine.core.DagInterpreter;
import com.station8.engine.core.RunOptions;
import com.station8.engine.core.RunOptionsCodec;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.repository.LineDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * #179 — 라인 정의의 즉시 실행(instance 생성)을 담당하는 sub-service.
 *
 * <p>책임:</p>
 * <ul>
 *   <li>{@link ConcurrencyStrategy} 평가 — 시작 시점 게이트 (#141/#177)</li>
 *   <li>{@link RunOptions} 인스턴스 override 적용 (#165 D1=A — instance override가 정의 default 무조건 무시)</li>
 *   <li>{@code U_LINE_INSTANCE} row INSERT + {@link DagInterpreter#startInstance} 호출</li>
 * </ul>
 *
 * <p>그래프 등록/수정은 본 sub-service의 책임이 아니며 {@link LineDefinitionPersistence}가 담당.
 * 본 클래스는 read-only 정의 조회와 instance row INSERT만 수행.</p>
 */
@Service
public class LineRunner {

    private static final Logger log = LoggerFactory.getLogger(LineRunner.class);

    private final LineDefinitionRepository definitionRepository;
    private final DagInterpreter dagInterpreter;
    private final JdbcTemplate jdbcTemplate;
    private final RunOptionsCodec runOptionsCodec;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param definitionRepository 정의 조회 repository (read-only 사용)
     * @param dagInterpreter       인스턴스 시작 시 DAG 워크플로 디스패치
     * @param jdbcTemplate         {@code U_LINE_INSTANCE} INSERT + FOR UPDATE lock 쿼리
     * @param runOptionsCodec      RunOptions ↔ JSON 변환
     */
    public LineRunner(LineDefinitionRepository definitionRepository,
                      DagInterpreter dagInterpreter,
                      JdbcTemplate jdbcTemplate,
                      RunOptionsCodec runOptionsCodec) {
        this.definitionRepository = definitionRepository;
        this.dagInterpreter = dagInterpreter;
        this.jdbcTemplate = jdbcTemplate;
        this.runOptionsCodec = runOptionsCodec;
    }

    /**
     * 즉시 실행. SKIP_IF_RUNNING 정책에 의한 skip을 결과 record로 반환 (예외 X).
     *
     * <p>#165 — {@link RunOptions#concurrencyPolicy()}가 비-null이면 정의의 default 정책을
     * 무조건 무시하고 instance override를 사용한다. PIPELINE_* dispatch 게이트는 정의 정책 그대로 유지.</p>
     *
     * @param definitionId 실행 대상 정의 ID
     * @param inputData    인스턴스 입력 데이터 (자유 텍스트, 액티비티가 직접 파싱)
     * @param options      인스턴스 옵션 ({@code null}이면 {@link RunOptions#defaults()})
     * @return skip된 경우 {@link RunResult#skipped}, 그렇지 않으면 {@link RunResult#started}
     * @throws IllegalArgumentException 정의가 없거나 이미 삭제된 경우
     */
    public RunResult runWithResult(String definitionId, String inputData, RunOptions options) {
        LineDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }

        RunOptions opt = options != null ? options : RunOptions.defaults();

        // #141, #177 — Concurrency strategy 평가 (시작 시점 게이트)
        // #165 — instance override가 있으면 정의 default를 무조건 무시 (D1=A)
        String effectivePolicyName = opt.concurrencyPolicy() != null
                ? opt.concurrencyPolicy().name()
                : def.concurrencyPolicy();
        ConcurrencyStrategy strategy = ConcurrencyStrategy.parse(effectivePolicyName);
        ConcurrencyStrategy.StartContext startCtx = new ConcurrencyStrategy.StartContext(
                def.definitionNm(),
                () -> findActiveInstanceWithLock(def.definitionNm())  // lazy — Concurrent는 SQL 안 부름
        );
        ConcurrencyStrategy.StartResult startResult = strategy.evaluateOnStart(startCtx);
        if (!startResult.allowed()) {
            log.warn("동시 실행 SKIP — definitionId={}, policy={}, conflicting={}",
                    definitionId, strategy.policyName(), startResult.conflictingInstanceId());
            return RunResult.skipped(startResult.reason(), startResult.conflictingInstanceId());
        }

        // RunOptionsCodec — 모두 default면 null 반환 → DB 컬럼 비움 (저장 공간/노이즈 절감)
        String optionsJson = runOptionsCodec.serializeToClob(opt);
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE
                  (ID, WORKFLOW_NAME, DEFINITION_ID, STATUS_ST, INPUT_DATA, RUN_OPTIONS, DEL_FL, START_DT, REG_DT)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId, def.definitionNm(), definitionId, inputData, optionsJson);  // #364 — 런타임 정의 스코프 조회용

        dagInterpreter.startInstance(definitionId, instanceId, inputData);
        log.info("DAG 즉시 실행: definitionId={}, instanceId={}, onFailure={}, concurrency={}",
                definitionId, instanceId, opt.onFailure(), strategy.policyName());
        return RunResult.started(instanceId);
    }

    /**
     * 같은 {@code workflow_name}의 RUNNING/PAUSED 인스턴스 1건 조회 (FOR UPDATE 락 — 동시 호출 race 방지).
     *
     * <p>트랜잭션 끝까지 락을 유지하여 두 호출이 동시에 들어와도 한쪽만 INSERT를 통과시킨다.
     * H2 / MariaDB / Oracle 모두 {@code FOR UPDATE}를 지원.</p>
     *
     * @param workflowName 정의 이름
     * @return 활성 인스턴스 ID 또는 {@code null}
     */
    private String findActiveInstanceWithLock(String workflowName) {
        // 같은 workflow_name 인스턴스 row를 read-and-lock하여 새 INSERT를 직렬화
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT ID FROM U_LINE_INSTANCE "
                        + "WHERE WORKFLOW_NAME = ? AND STATUS_ST IN ('RUNNING', 'PAUSED') "
                        + "AND DEL_FL = 'N' "
                        + "FOR UPDATE",
                String.class, workflowName);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
