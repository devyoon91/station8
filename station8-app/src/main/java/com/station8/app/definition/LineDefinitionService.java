package com.station8.app.definition;

import com.station8.engine.core.ConcurrencyPolicy;
import com.station8.engine.core.DagInterpreter;
import com.station8.engine.core.DagValidator;
import com.station8.engine.core.LineRegistry;
import com.station8.engine.core.RunOptions;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineStation;
import com.station8.app.security.LineAclRepository;
import com.station8.app.security.LineUser;
import com.station8.app.security.LineUserRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DAG 정의 등록/수정/삭제 + 즉시 실행을 담당하는 서비스 레이어.
 * 검증은 {@link DagValidator}, 그래프 시작은 {@link DagInterpreter}에 위임한다.
 */
@Service
public class LineDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(LineDefinitionService.class);

    private final LineDefinitionRepository definitionRepository;
    private final DagValidator dagValidator;
    private final LineRegistry workflowRegistry;
    private final DagInterpreter dagInterpreter;
    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;
    private final LineAclRepository aclRepository;
    private final LineUserRepository userRepository;

    public LineDefinitionService(LineDefinitionRepository definitionRepository,
                                     DagValidator dagValidator,
                                     LineRegistry workflowRegistry,
                                     DagInterpreter dagInterpreter,
                                     JdbcTemplate jdbcTemplate,
                                     JsonUtil jsonUtil,
                                     LineAclRepository aclRepository,
                                     LineUserRepository userRepository) {
        this.definitionRepository = definitionRepository;
        this.dagValidator = dagValidator;
        this.workflowRegistry = workflowRegistry;
        this.dagInterpreter = dagInterpreter;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
        this.aclRepository = aclRepository;
        this.userRepository = userRepository;
    }

    /**
     * 새 DAG 정의를 등록한다. 같은 ``definitionNm``이 이미 있으면 새 버전으로 추가.
     *
     * @return 생성된 definitionId
     */
    @Transactional
    public String createDefinition(DagDefinitionRequest req) {
        if (req.definitionNm() == null || req.definitionNm().isBlank()) {
            throw new IllegalArgumentException("definitionNm 필수");
        }
        if (req.nodes() == null || req.nodes().isEmpty()) {
            throw new IllegalArgumentException("nodes 필수");
        }

        String definitionId = UUID.randomUUID().toString();
        int nextVersion = definitionRepository.findMaxVersionByName(req.definitionNm()) + 1;

        // 검증을 위한 엔티티 변환 (저장 전에 위반 여부 확인)
        List<LineStation> nodes = req.nodes().stream().map(n -> new LineStation(
                n.nodeId(), definitionId, n.nodeNm(), n.activityNm(), n.inputParams(),
                serializeBindings(n.datasourceBindings()),
                n.posX(), n.posY(), "Y", "Y", "N", null, null, null, null
        )).toList();
        List<LineTrack> edges = req.edges() == null ? List.of()
                : req.edges().stream().map(e -> new LineTrack(
                e.edgeId(), definitionId, e.fromNodeId(), e.toNodeId(), e.conditionExpr(),
                "Y", "Y", "N", null, null, null, null
        )).toList();

        // 검증 — 위반 시 LineEngineException(DAG_INVALID) 으로 실패
        dagValidator.validate(nodes, edges, workflowRegistry.getActivityNames());

        // 정의 + 역 + 엣지 저장 — #138 SLA / #141 동시 실행 정책은 req에서 받아 저장
        LineDefinition def = new LineDefinition(
                definitionId, req.definitionNm(), req.description(),
                nextVersion, "Y",
                req.slaSeconds(), req.slaAction(),
                req.concurrencyPolicy(),
                "Y", "Y", "N",
                null, "api", null, null
        );
        definitionRepository.insertDefinition(def);
        for (LineStation n : nodes) definitionRepository.insertNode(n);
        for (LineTrack e : edges) definitionRepository.insertEdge(e);

        log.info("DAG 정의 등록: id={}, nm={}, version={}, nodes={}, edges={}",
                definitionId, req.definitionNm(), nextVersion, nodes.size(), edges.size());

        // #140 — 정의 생성자에게 ADMIN 자동 부여 (현재 인증된 사용자가 있을 때만)
        autoGrantAdminToCreator(definitionId);

        return definitionId;
    }

    /**
     * #140 — 정의 생성 시 현재 인증된 사용자에게 ADMIN 권한 부여.
     * 인증 컨텍스트가 없거나(시스템/테스트) 사용자가 DB에 없으면 skip.
     */
    private void autoGrantAdminToCreator(String definitionId) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()
                    || "anonymousUser".equals(auth.getPrincipal())) {
                log.debug("[#140] 정의 생성 — 인증 컨텍스트 없음, ADMIN auto-grant skip: id={}", definitionId);
                return;
            }
            String username = auth.getName();
            LineUser user = userRepository.findByUsername(username);
            if (user == null) {
                log.warn("[#140] 정의 생성 — 사용자 '{}' DB에 없음, ADMIN auto-grant skip: id={}",
                        username, definitionId);
                return;
            }
            aclRepository.grant(definitionId, user.id(), "ADMIN", username);
            log.info("[#140] 정의 생성자에게 ADMIN 자동 부여: definitionId={}, user={}", definitionId, username);
        } catch (Exception ex) {
            // ACL 부여 실패가 정의 생성 실패로 이어지지 않도록 — 정의는 성공, 권한은 운영자가 수동 grant
            log.error("[#140] ADMIN auto-grant 실패 (definition은 그대로 저장됨): id={}", definitionId, ex);
        }
    }

    @Transactional(readOnly = true)
    public DagDefinitionResponse getDefinition(String definitionId) {
        LineDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }
        List<LineStation> nodes = definitionRepository.findNodesByDefinition(definitionId);
        List<LineTrack> edges = definitionRepository.findEdgesByDefinition(definitionId);
        return new DagDefinitionResponse(
                def.id(), def.definitionNm(), def.description(),
                def.versionNo(), def.activeFl(),
                def.slaSeconds(), def.slaAction(),
                def.concurrencyPolicy(),
                nodes.stream().map(n -> new DagDefinitionRequest.NodeDef(
                        n.id(), n.nodeNm(), n.activityNm(), n.inputParams(),
                        n.posXNo(), n.posYNo(),
                        jsonUtil.fromJsonToStringMap(n.datasourceBindings()))).toList(),
                edges.stream().map(e -> new DagDefinitionRequest.EdgeDef(
                        e.id(), e.fromNodeId(), e.toNodeId(), e.conditionExpr())).toList()
        );
    }

    /**
     * 정의의 역/엣지를 통째로 교체한다 (메타 + 그래프). 버전은 증가시키지 않으며 같은 ID 유지.
     * 새 버전으로 저장하고 싶으면 createDefinition을 다시 호출.
     */
    @Transactional
    public void replaceDefinition(String definitionId, DagDefinitionRequest req) {
        LineDefinition existing = definitionRepository.findDefinitionById(definitionId);
        if (existing == null || "Y".equals(existing.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }
        List<LineStation> nodes = req.nodes().stream().map(n -> new LineStation(
                n.nodeId(), definitionId, n.nodeNm(), n.activityNm(), n.inputParams(),
                serializeBindings(n.datasourceBindings()),
                n.posX(), n.posY(), "Y", "Y", "N", null, null, null, null
        )).toList();
        List<LineTrack> edges = req.edges() == null ? List.of()
                : req.edges().stream().map(e -> new LineTrack(
                e.edgeId(), definitionId, e.fromNodeId(), e.toNodeId(), e.conditionExpr(),
                "Y", "Y", "N", null, null, null, null
        )).toList();
        dagValidator.validate(nodes, edges, workflowRegistry.getActivityNames());

        definitionRepository.updateDefinitionMeta(definitionId, req.description(), null);
        definitionRepository.updateDefinitionSla(definitionId, req.slaSeconds(), req.slaAction());
        definitionRepository.updateDefinitionConcurrency(definitionId, req.concurrencyPolicy());
        definitionRepository.softDeleteEdgesByDefinition(definitionId);
        definitionRepository.softDeleteNodesByDefinition(definitionId);
        for (LineStation n : nodes) definitionRepository.insertNode(n);
        for (LineTrack e : edges) definitionRepository.insertEdge(e);

        log.info("DAG 정의 교체: id={}, nodes={}, edges={}, sla={}s/{}, concurrency={}",
                definitionId, nodes.size(), edges.size(), req.slaSeconds(), req.slaAction(),
                req.concurrencyPolicy());
    }

    @Transactional
    public void deleteDefinition(String definitionId) {
        LineDefinition existing = definitionRepository.findDefinitionById(definitionId);
        if (existing == null || "Y".equals(existing.delFl())) {
            return; // 멱등 삭제
        }
        definitionRepository.softDeleteEdgesByDefinition(definitionId);
        definitionRepository.softDeleteNodesByDefinition(definitionId);
        definitionRepository.softDeleteDefinition(definitionId);
        log.info("DAG 정의 소프트 삭제: id={}", definitionId);
    }

    /**
     * 즉시 실행 (후방 호환) — 옵션 없이 default(continue / 빈 params / 전역 webhook).
     *
     * @return 생성된 instanceId
     */
    @Transactional
    public String runDefinition(String definitionId, String inputData) {
        return runDefinition(definitionId, inputData, RunOptions.defaults());
    }

    /**
     * 즉시 실행 + 인스턴스 단위 옵션 (#134 D1=γ).
     *
     * <p>{@code options}는 JSON으로 직렬화되어 {@code U_LINE_INSTANCE.RUN_OPTIONS}에 저장된다.
     * 후방 호환 — null이면 {@link RunOptions#defaults()}.</p>
     *
     * <p>#141 — 정의의 {@code SKIP_IF_RUNNING} 정책 활성 시 같은 정의의 RUNNING/PAUSED 인스턴스가 있으면
     * skip 처리되어 {@link IllegalStateException}을 던진다. SKIP을 정상 흐름으로 처리하려면
     * {@link #runDefinitionWithResult}를 사용.</p>
     */
    @Transactional
    public String runDefinition(String definitionId, String inputData, RunOptions options) {
        RunResult result = runDefinitionWithResult(definitionId, inputData, options);
        if (result.skipped()) {
            // 후방 호환 — 기존 callers는 String 기대. SKIP은 IllegalStateException으로.
            throw new IllegalStateException("동시 실행 SKIP: " + result.reason()
                    + " (conflicting instance: " + result.conflictingInstanceId() + ")");
        }
        return result.instanceId();
    }

    /**
     * #141 — 즉시 실행. SKIP_IF_RUNNING 정책 시 skip 결과를 반환 (예외 X).
     */
    @Transactional
    public RunResult runDefinitionWithResult(String definitionId, String inputData, RunOptions options) {
        LineDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }

        // #141 — SKIP_IF_RUNNING 체크 (정의 정책)
        ConcurrencyPolicy policy = ConcurrencyPolicy.parse(def.concurrencyPolicy());
        if (policy == ConcurrencyPolicy.SKIP_IF_RUNNING) {
            String conflicting = findActiveInstanceWithLock(def.definitionNm());
            if (conflicting != null) {
                String reason = "Definition '" + def.definitionNm() + "' has an active instance ("
                        + conflicting + ") with policy SKIP_IF_RUNNING";
                log.warn("[#141] 동시 실행 SKIP — definitionId={}, conflictingInstance={}",
                        definitionId, conflicting);
                return RunResult.skipped(reason, conflicting);
            }
        }

        RunOptions opt = options != null ? options : RunOptions.defaults();
        String optionsJson = serializeRunOptions(opt);
        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_LINE_INSTANCE
                  (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, RUN_OPTIONS, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
                VALUES (?, ?, 'RUNNING', ?, ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId, def.definitionNm(), inputData, optionsJson);

        dagInterpreter.startInstance(definitionId, instanceId, inputData);
        log.info("DAG 즉시 실행: definitionId={}, instanceId={}, onFailure={}, concurrency={}",
                definitionId, instanceId, opt.onFailure(), policy);
        return RunResult.started(instanceId);
    }

    /**
     * #141 — 같은 workflow_name의 RUNNING/PAUSED 인스턴스 1건 조회 (FOR UPDATE 락 — 동시 호출 race 방지).
     * 트랜잭션 끝까지 락 유지 → 두 호출이 동시에 들어와도 한쪽만 INSERT 통과.
     */
    private String findActiveInstanceWithLock(String workflowName) {
        // FOR UPDATE는 트랜잭션 격리 + 락을 보장. 비기존 행도 락 시도 — H2 / MariaDB / Oracle 모두 지원.
        // 같은 workflow_name 인스턴스 row를 read-and-lock하여 새 INSERT를 직렬화.
        java.util.List<String> ids = jdbcTemplate.queryForList(
                "SELECT ID FROM U_LINE_INSTANCE "
                        + "WHERE WORKFLOW_NAME = ? AND STATUS_ST IN ('RUNNING', 'PAUSED') "
                        + "AND DEL_FL = 'N' "
                        + "FOR UPDATE",
                String.class, workflowName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** RunOptions → JSON. 모두 default면 null 반환 (DB 컬럼 비움). */
    private String serializeRunOptions(RunOptions opt) {
        boolean isDefault = opt.onFailure() == RunOptions.OnFailure.CONTINUE
                && (opt.runtimeParams() == null || opt.runtimeParams().isEmpty())
                && (opt.notificationWebhookUrl() == null || opt.notificationWebhookUrl().isBlank())
                && opt.slaSeconds() == null
                && opt.slaAction() == null;
        if (isDefault) return null;
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("onFailure", opt.onFailure().name());
        if (opt.runtimeParams() != null && !opt.runtimeParams().isEmpty()) {
            map.put("runtimeParams", opt.runtimeParams());
        }
        if (opt.notificationWebhookUrl() != null && !opt.notificationWebhookUrl().isBlank()) {
            map.put("notificationWebhookUrl", opt.notificationWebhookUrl());
        }
        // #138 — SLA override
        if (opt.slaSeconds() != null) map.put("slaSeconds", opt.slaSeconds());
        if (opt.slaAction() != null) map.put("slaAction", opt.slaAction().name());
        return jsonUtil.toJson(map);
    }

    /**
     * NodeDef.datasourceBindings(Map) → DB 저장용 JSON 문자열. null/빈 맵이면 null 저장.
     */
    private String serializeBindings(Map<String, String> bindings) {
        if (bindings == null || bindings.isEmpty()) return null;
        return jsonUtil.toJson(bindings);
    }
}
