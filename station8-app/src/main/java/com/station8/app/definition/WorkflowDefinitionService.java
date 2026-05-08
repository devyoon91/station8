package com.station8.app.definition;

import com.station8.engine.core.DagInterpreter;
import com.station8.engine.core.DagValidator;
import com.station8.engine.core.WorkflowRegistry;
import com.station8.engine.entity.WorkflowDefinition;
import com.station8.engine.entity.WorkflowEdge;
import com.station8.engine.entity.WorkflowNode;
import com.station8.engine.repository.WorkflowDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * DAG 정의 등록/수정/삭제 + 즉시 실행을 담당하는 서비스 레이어.
 * 검증은 {@link DagValidator}, 그래프 시작은 {@link DagInterpreter}에 위임한다.
 */
@Service
public class WorkflowDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionService.class);

    private final WorkflowDefinitionRepository definitionRepository;
    private final DagValidator dagValidator;
    private final WorkflowRegistry workflowRegistry;
    private final DagInterpreter dagInterpreter;
    private final JdbcTemplate jdbcTemplate;

    public WorkflowDefinitionService(WorkflowDefinitionRepository definitionRepository,
                                     DagValidator dagValidator,
                                     WorkflowRegistry workflowRegistry,
                                     DagInterpreter dagInterpreter,
                                     JdbcTemplate jdbcTemplate) {
        this.definitionRepository = definitionRepository;
        this.dagValidator = dagValidator;
        this.workflowRegistry = workflowRegistry;
        this.dagInterpreter = dagInterpreter;
        this.jdbcTemplate = jdbcTemplate;
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
        List<WorkflowNode> nodes = req.nodes().stream().map(n -> new WorkflowNode(
                n.nodeId(), definitionId, n.nodeNm(), n.activityNm(), n.inputParams(),
                n.posX(), n.posY(), "Y", "Y", "N", null, null, null, null
        )).toList();
        List<WorkflowEdge> edges = req.edges() == null ? List.of()
                : req.edges().stream().map(e -> new WorkflowEdge(
                e.edgeId(), definitionId, e.fromNodeId(), e.toNodeId(), e.conditionExpr(),
                "Y", "Y", "N", null, null, null, null
        )).toList();

        // 검증 — 위반 시 WorkflowEngineException(DAG_INVALID) 으로 실패
        dagValidator.validate(nodes, edges, workflowRegistry.getActivityNames());

        // 정의 + 노드 + 엣지 저장
        WorkflowDefinition def = new WorkflowDefinition(
                definitionId, req.definitionNm(), req.description(),
                nextVersion, "Y", "Y", "Y", "N",
                null, "api", null, null
        );
        definitionRepository.insertDefinition(def);
        for (WorkflowNode n : nodes) definitionRepository.insertNode(n);
        for (WorkflowEdge e : edges) definitionRepository.insertEdge(e);

        log.info("DAG 정의 등록: id={}, nm={}, version={}, nodes={}, edges={}",
                definitionId, req.definitionNm(), nextVersion, nodes.size(), edges.size());
        return definitionId;
    }

    @Transactional(readOnly = true)
    public DagDefinitionResponse getDefinition(String definitionId) {
        WorkflowDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }
        List<WorkflowNode> nodes = definitionRepository.findNodesByDefinition(definitionId);
        List<WorkflowEdge> edges = definitionRepository.findEdgesByDefinition(definitionId);
        return new DagDefinitionResponse(
                def.id(), def.definitionNm(), def.description(),
                def.versionNo(), def.activeFl(),
                nodes.stream().map(n -> new DagDefinitionRequest.NodeDef(
                        n.id(), n.nodeNm(), n.activityNm(), n.inputParams(),
                        n.posXNo(), n.posYNo())).toList(),
                edges.stream().map(e -> new DagDefinitionRequest.EdgeDef(
                        e.id(), e.fromNodeId(), e.toNodeId(), e.conditionExpr())).toList()
        );
    }

    /**
     * 정의의 노드/엣지를 통째로 교체한다 (메타 + 그래프). 버전은 증가시키지 않으며 같은 ID 유지.
     * 새 버전으로 저장하고 싶으면 createDefinition을 다시 호출.
     */
    @Transactional
    public void replaceDefinition(String definitionId, DagDefinitionRequest req) {
        WorkflowDefinition existing = definitionRepository.findDefinitionById(definitionId);
        if (existing == null || "Y".equals(existing.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }
        List<WorkflowNode> nodes = req.nodes().stream().map(n -> new WorkflowNode(
                n.nodeId(), definitionId, n.nodeNm(), n.activityNm(), n.inputParams(),
                n.posX(), n.posY(), "Y", "Y", "N", null, null, null, null
        )).toList();
        List<WorkflowEdge> edges = req.edges() == null ? List.of()
                : req.edges().stream().map(e -> new WorkflowEdge(
                e.edgeId(), definitionId, e.fromNodeId(), e.toNodeId(), e.conditionExpr(),
                "Y", "Y", "N", null, null, null, null
        )).toList();
        dagValidator.validate(nodes, edges, workflowRegistry.getActivityNames());

        definitionRepository.updateDefinitionMeta(definitionId, req.description(), null);
        definitionRepository.softDeleteEdgesByDefinition(definitionId);
        definitionRepository.softDeleteNodesByDefinition(definitionId);
        for (WorkflowNode n : nodes) definitionRepository.insertNode(n);
        for (WorkflowEdge e : edges) definitionRepository.insertEdge(e);

        log.info("DAG 정의 교체: id={}, nodes={}, edges={}", definitionId, nodes.size(), edges.size());
    }

    @Transactional
    public void deleteDefinition(String definitionId) {
        WorkflowDefinition existing = definitionRepository.findDefinitionById(definitionId);
        if (existing == null || "Y".equals(existing.delFl())) {
            return; // 멱등 삭제
        }
        definitionRepository.softDeleteEdgesByDefinition(definitionId);
        definitionRepository.softDeleteNodesByDefinition(definitionId);
        definitionRepository.softDeleteDefinition(definitionId);
        log.info("DAG 정의 소프트 삭제: id={}", definitionId);
    }

    /**
     * 즉시 실행: 인스턴스 생성 후 인터프리터에 위임.
     *
     * @return 생성된 instanceId
     */
    @Transactional
    public String runDefinition(String definitionId, String inputData) {
        WorkflowDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }

        String instanceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO U_WF_INSTANCE
                  (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
                VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId, def.definitionNm(), inputData);

        dagInterpreter.startInstance(definitionId, instanceId, inputData);
        log.info("DAG 즉시 실행: definitionId={}, instanceId={}", definitionId, instanceId);
        return instanceId;
    }
}
