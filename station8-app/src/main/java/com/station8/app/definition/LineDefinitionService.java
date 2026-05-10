package com.station8.app.definition;

import com.station8.engine.core.DagInterpreter;
import com.station8.engine.core.DagValidator;
import com.station8.engine.core.LineRegistry;
import com.station8.engine.core.RunOptions;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineStation;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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

    public LineDefinitionService(LineDefinitionRepository definitionRepository,
                                     DagValidator dagValidator,
                                     LineRegistry workflowRegistry,
                                     DagInterpreter dagInterpreter,
                                     JdbcTemplate jdbcTemplate,
                                     JsonUtil jsonUtil) {
        this.definitionRepository = definitionRepository;
        this.dagValidator = dagValidator;
        this.workflowRegistry = workflowRegistry;
        this.dagInterpreter = dagInterpreter;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
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

        // 정의 + 역 + 엣지 저장
        LineDefinition def = new LineDefinition(
                definitionId, req.definitionNm(), req.description(),
                nextVersion, "Y", "Y", "Y", "N",
                null, "api", null, null
        );
        definitionRepository.insertDefinition(def);
        for (LineStation n : nodes) definitionRepository.insertNode(n);
        for (LineTrack e : edges) definitionRepository.insertEdge(e);

        log.info("DAG 정의 등록: id={}, nm={}, version={}, nodes={}, edges={}",
                definitionId, req.definitionNm(), nextVersion, nodes.size(), edges.size());
        return definitionId;
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
        definitionRepository.softDeleteEdgesByDefinition(definitionId);
        definitionRepository.softDeleteNodesByDefinition(definitionId);
        for (LineStation n : nodes) definitionRepository.insertNode(n);
        for (LineTrack e : edges) definitionRepository.insertEdge(e);

        log.info("DAG 정의 교체: id={}, nodes={}, edges={}", definitionId, nodes.size(), edges.size());
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
     */
    @Transactional
    public String runDefinition(String definitionId, String inputData, RunOptions options) {
        LineDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
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
        log.info("DAG 즉시 실행: definitionId={}, instanceId={}, onFailure={}",
                definitionId, instanceId, opt.onFailure());
        return instanceId;
    }

    /** RunOptions → JSON. 모두 default면 null 반환 (DB 컬럼 비움). */
    private String serializeRunOptions(RunOptions opt) {
        boolean isDefault = opt.onFailure() == RunOptions.OnFailure.CONTINUE
                && (opt.runtimeParams() == null || opt.runtimeParams().isEmpty())
                && (opt.notificationWebhookUrl() == null || opt.notificationWebhookUrl().isBlank());
        if (isDefault) return null;
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("onFailure", opt.onFailure().name());
        if (opt.runtimeParams() != null && !opt.runtimeParams().isEmpty()) {
            map.put("runtimeParams", opt.runtimeParams());
        }
        if (opt.notificationWebhookUrl() != null && !opt.notificationWebhookUrl().isBlank()) {
            map.put("notificationWebhookUrl", opt.notificationWebhookUrl());
        }
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
