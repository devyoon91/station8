package com.station8.app.definition;

import com.station8.engine.core.DagValidator;
import com.station8.engine.core.LineRegistry;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineStation;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * #179 — DAG 정의의 row + 그래프(노드/엣지) CRUD를 담당하는 sub-service.
 *
 * <p>{@link LineDefinitionService} Facade가 호출 순서를 조율하며, 본 클래스는
 * 정의 row({@code U_LINE_DEFINITION}) + 노드 row({@code U_LINE_STATION}) +
 * 엣지 row({@code U_LINE_TRACK}) 직접 영속성만 담당한다.</p>
 *
 * <h3>책임 분리 (#179)</h3>
 * <ul>
 *   <li>태그({@code U_LINE_DEFINITION_TAG}) 영속성은 {@link LineDefinitionMetadata}로 위임.</li>
 *   <li>ACL auto-grant는 {@link LineDefinitionAclBootstrap}로 위임.</li>
 *   <li>즉시 실행은 {@link LineRunner}로 위임.</li>
 * </ul>
 *
 * <p>그래프 위상 검증은 {@link DagValidator}에 위임. 위반 시 {@code LineEngineException(DAG_INVALID)}.
 * 입력 검증(필드 단위)은 호출 측({@code @Valid})에서 이미 수행되었다고 가정.</p>
 */
@Service
public class LineDefinitionPersistence {

    private static final Logger log = LoggerFactory.getLogger(LineDefinitionPersistence.class);

    private final LineDefinitionRepository definitionRepository;
    private final DagValidator dagValidator;
    private final LineRegistry workflowRegistry;
    private final JsonUtil jsonUtil;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param definitionRepository 정의/노드/엣지/태그 영속성 repository
     * @param dagValidator         그래프 위상 검증기 — 사이클/orphan 등 위반 시 예외
     * @param workflowRegistry     등록된 activity 이름 lookup
     * @param jsonUtil             NodeDef.datasourceBindings 직렬화용 JSON util
     */
    public LineDefinitionPersistence(LineDefinitionRepository definitionRepository,
                                     DagValidator dagValidator,
                                     LineRegistry workflowRegistry,
                                     JsonUtil jsonUtil) {
        this.definitionRepository = definitionRepository;
        this.dagValidator = dagValidator;
        this.workflowRegistry = workflowRegistry;
        this.jsonUtil = jsonUtil;
    }

    /**
     * 새 DAG 정의 + 노드 + 엣지 일괄 등록. 같은 {@code definitionNm}이 이미 있으면 새 버전으로 자동 증가.
     *
     * <p>본 메서드는 row 단위 검증만 수행 — 입력 필드 NotBlank/NotEmpty 같은 표면 검증은
     * 호출 측에서 이미 검증되었다고 가정한다. 그래프 위상 검증은 {@link DagValidator}에 위임.</p>
     *
     * @param req 정의 등록 요청 (필드 검증 완료 가정)
     * @return 새로 생성된 definitionId (UUID)
     * @throws IllegalArgumentException 표면 필드(이름/노드)가 누락된 경우
     */
    public String createDefinition(DagDefinitionRequest req) {
        if (req.definitionNm() == null || req.definitionNm().isBlank()) {
            throw new IllegalArgumentException("definitionNm 필수");
        }
        if (req.nodes() == null || req.nodes().isEmpty()) {
            throw new IllegalArgumentException("nodes 필수");
        }

        String definitionId = UUID.randomUUID().toString();
        int nextVersion = definitionRepository.findMaxVersionByName(req.definitionNm()) + 1;

        // 엔티티 변환 후 저장 전 검증 — 위반 시 LineEngineException(DAG_INVALID)으로 빠짐
        List<LineStation> nodes = toStations(definitionId, req);
        List<LineTrack> edges = toTracks(definitionId, req);
        dagValidator.validate(nodes, edges, workflowRegistry.getActivityNames());

        // 정의 row 저장 — #138 SLA / #141 동시 실행 정책은 req에서 받아 그대로 저장.
        // #168 — Phase 1에서는 projectId 미지정 → default project 할당 (Phase 2에서 UI/API로 명시 선택).
        LineDefinition def = new LineDefinition(
                definitionId, req.definitionNm(), req.description(),
                nextVersion, "Y",
                req.slaSeconds(), req.slaAction(),
                req.concurrencyPolicy(),
                com.station8.engine.entity.LineProject.DEFAULT_PROJECT_ID,
                "N",
                null, "api", null, null
        );
        definitionRepository.insertDefinition(def);
        for (LineStation n : nodes) {
            definitionRepository.insertNode(n);
        }
        for (LineTrack e : edges) {
            definitionRepository.insertEdge(e);
        }

        log.info("DAG 정의 등록 (persistence): id={}, nm={}, version={}, nodes={}, edges={}",
                definitionId, req.definitionNm(), nextVersion, nodes.size(), edges.size());
        return definitionId;
    }

    /**
     * 정의의 메타(설명/SLA/Concurrency) + 그래프(노드/엣지)를 통째로 교체.
     *
     * <p>버전은 증가시키지 않으며 같은 ID를 유지한다. 새 버전으로 저장하고 싶으면
     * {@link #createDefinition}을 다시 호출하라.</p>
     *
     * @param definitionId 교체 대상 정의 ID (존재해야 함)
     * @param req          새 정의 페이로드
     * @throws IllegalArgumentException 정의가 없거나 이미 삭제된 경우
     */
    public void replaceDefinition(String definitionId, DagDefinitionRequest req) {
        LineDefinition existing = definitionRepository.findDefinitionById(definitionId);
        if (existing == null || "Y".equals(existing.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }
        List<LineStation> nodes = toStations(definitionId, req);
        List<LineTrack> edges = toTracks(definitionId, req);
        dagValidator.validate(nodes, edges, workflowRegistry.getActivityNames());

        definitionRepository.updateDefinitionMeta(definitionId, req.description(), null);
        definitionRepository.updateDefinitionSla(definitionId, req.slaSeconds(), req.slaAction());
        definitionRepository.updateDefinitionConcurrency(definitionId, req.concurrencyPolicy());
        definitionRepository.softDeleteEdgesByDefinition(definitionId);
        definitionRepository.softDeleteNodesByDefinition(definitionId);
        for (LineStation n : nodes) {
            definitionRepository.insertNode(n);
        }
        for (LineTrack e : edges) {
            definitionRepository.insertEdge(e);
        }

        log.info("DAG 정의 교체 (persistence): id={}, nodes={}, edges={}, sla={}s/{}, concurrency={}",
                definitionId, nodes.size(), edges.size(), req.slaSeconds(), req.slaAction(),
                req.concurrencyPolicy());
    }

    /**
     * 정의 + 노드 + 엣지를 모두 소프트 삭제. 이미 삭제된 경우 멱등 (no-op).
     *
     * @param definitionId 삭제 대상 정의 ID
     */
    public void softDelete(String definitionId) {
        LineDefinition existing = definitionRepository.findDefinitionById(definitionId);
        if (existing == null || "Y".equals(existing.delFl())) {
            return;
        }
        definitionRepository.softDeleteEdgesByDefinition(definitionId);
        definitionRepository.softDeleteNodesByDefinition(definitionId);
        definitionRepository.softDeleteDefinition(definitionId);
        log.info("DAG 정의 소프트 삭제 (persistence): id={}", definitionId);
    }

    /**
     * 정의 단건 조회 — 메타 + 노드 + 엣지 + 태그를 합쳐 {@link DagDefinitionResponse}로 반환.
     *
     * <p>태그 lookup은 read-only 작업이라 본 sub-service에서 직접 호출 (별도 sub-service round-trip 회피).</p>
     *
     * @param definitionId 조회 대상 정의 ID
     * @return 정의 응답 DTO
     * @throws IllegalArgumentException 정의가 없거나 이미 삭제된 경우
     */
    public DagDefinitionResponse getDefinition(String definitionId) {
        LineDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }
        List<LineStation> nodes = definitionRepository.findNodesByDefinition(definitionId);
        List<LineTrack> edges = definitionRepository.findEdgesByDefinition(definitionId);
        List<String> tags = definitionRepository.findTagsForDefinition(definitionId);
        return new DagDefinitionResponse(
                def.id(), def.definitionNm(), def.description(),
                def.versionNo(), def.activeFl(),
                def.slaSeconds(), def.slaAction(),
                def.concurrencyPolicy(),
                def.projectId(),  // #168
                tags,
                nodes.stream().map(n -> new DagDefinitionRequest.NodeDef(
                        n.id(), n.nodeNm(), n.activityNm(), n.inputParams(),
                        n.posXNo(), n.posYNo(),
                        jsonUtil.fromJsonToStringMap(n.datasourceBindings()))).toList(),
                edges.stream().map(e -> new DagDefinitionRequest.EdgeDef(
                        e.id(), e.fromNodeId(), e.toNodeId(), e.conditionExpr())).toList()
        );
    }

    /** Request의 NodeDef 목록을 LineStation 엔티티 목록으로 변환. */
    private List<LineStation> toStations(String definitionId, DagDefinitionRequest req) {
        return req.nodes().stream().map(n -> new LineStation(
                n.nodeId(), definitionId, n.nodeNm(), n.activityNm(), n.inputParams(),
                serializeBindings(n.datasourceBindings()),
                n.posX(), n.posY(), "N", null, null, null, null
        )).toList();
    }

    /** Request의 EdgeDef 목록을 LineTrack 엔티티 목록으로 변환. null/빈 목록 허용. */
    private List<LineTrack> toTracks(String definitionId, DagDefinitionRequest req) {
        if (req.edges() == null) {
            return List.of();
        }
        return req.edges().stream().map(e -> new LineTrack(
                e.edgeId(), definitionId, e.fromNodeId(), e.toNodeId(), e.conditionExpr(),
                "N", null, null, null, null
        )).toList();
    }

    /**
     * NodeDef.datasourceBindings(Map) → DB 저장용 JSON 문자열. null/빈 맵이면 null 저장.
     */
    private String serializeBindings(Map<String, String> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        return jsonUtil.toJson(bindings);
    }
}
