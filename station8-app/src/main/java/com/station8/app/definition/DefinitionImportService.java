package com.station8.app.definition;

import com.station8.engine.repository.LineDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * #193 — DAG 정의 import 오케스트레이션.
 *
 * <p>{@link DefinitionExportPayload}를 받아 {@link DagDefinitionRequest}로 변환한 뒤
 * {@link LineDefinitionService#createDefinition}로 위임. 이름 충돌은 {@link ConflictPolicy}로
 * 분기.</p>
 *
 * <h3>검증 책임</h3>
 * <ul>
 *   <li>schemaVersion — 본 서비스가 직접 확인 ({@code "1"}만 허용)</li>
 *   <li>activity 존재 / 위상 / datasource 등 — {@code DagValidator}가 createDefinition 안에서 수행</li>
 * </ul>
 */
@Service
public class DefinitionImportService {

    private static final Logger log = LoggerFactory.getLogger(DefinitionImportService.class);

    /** 충돌 발생 시 rename suffix를 최대 몇 회까지 시도할지. */
    private static final int RENAME_MAX_ATTEMPTS = 100;

    private final LineDefinitionService definitionService;
    private final LineDefinitionRepository definitionRepository;

    public DefinitionImportService(LineDefinitionService definitionService,
                                   LineDefinitionRepository definitionRepository) {
        this.definitionService = definitionService;
        this.definitionRepository = definitionRepository;
    }

    /**
     * 정의 import — schemaVersion 검증 + 충돌 정책 적용 + createDefinition 위임.
     *
     * @param payload 클라이언트가 업로드한 export 페이로드
     * @param policy  이름 충돌 시 정책
     * @return 생성된 정의 ID + 적용된 최종 이름
     * @throws IllegalArgumentException schemaVersion 미지원 또는 reject 정책에서 충돌 시
     */
    public ImportResult importDefinition(DefinitionExportPayload payload, ConflictPolicy policy) {
        if (payload == null) {
            throw new IllegalArgumentException("payload 필수");
        }
        if (!DefinitionExportPayload.CURRENT_SCHEMA_VERSION.equals(payload.schemaVersion())) {
            throw new IllegalArgumentException("지원하지 않는 schemaVersion: "
                    + payload.schemaVersion() + " (현재 지원: "
                    + DefinitionExportPayload.CURRENT_SCHEMA_VERSION + ")");
        }
        if (payload.definitionNm() == null || payload.definitionNm().isBlank()) {
            throw new IllegalArgumentException("definitionNm 필수");
        }

        String effectiveName = resolveName(payload.definitionNm(), policy);
        // nodeId / edgeId 재발급 — U_LINE_STATION.ID와 U_LINE_TRACK.ID가 글로벌 PK라
        // 다른 정의에서 같은 외부 nodeId를 재사용하면 PK 충돌. import는 항상 새 ID를 발급하고,
        // edges의 fromNodeId/toNodeId 참조도 일관되게 remap한다.
        Map<String, String> nodeIdMap = new HashMap<>();
        List<DagDefinitionRequest.NodeDef> remappedNodes = new ArrayList<>(payload.nodes().size());
        for (DagDefinitionRequest.NodeDef n : payload.nodes()) {
            String fresh = "n-" + UUID.randomUUID();
            nodeIdMap.put(n.nodeId(), fresh);
            remappedNodes.add(new DagDefinitionRequest.NodeDef(
                    fresh, n.nodeNm(), n.activityNm(), n.inputParams(),
                    n.posX(), n.posY(), n.datasourceBindings()));
        }
        List<DagDefinitionRequest.EdgeDef> remappedEdges = new ArrayList<>(
                payload.edges() == null ? 0 : payload.edges().size());
        if (payload.edges() != null) {
            for (DagDefinitionRequest.EdgeDef e : payload.edges()) {
                String fromMapped = nodeIdMap.get(e.fromNodeId());
                String toMapped = nodeIdMap.get(e.toNodeId());
                if (fromMapped == null || toMapped == null) {
                    throw new IllegalArgumentException(
                            "edge가 참조하는 nodeId가 nodes 목록에 없음: " + e.fromNodeId() + "→" + e.toNodeId());
                }
                remappedEdges.add(new DagDefinitionRequest.EdgeDef(
                        "e-" + UUID.randomUUID(), fromMapped, toMapped, e.conditionExpr()));
            }
        }
        DagDefinitionRequest req = new DagDefinitionRequest(
                effectiveName,
                payload.description(),
                payload.slaSeconds(),
                payload.slaAction(),
                payload.concurrencyPolicy(),
                payload.tags(),
                remappedNodes,
                remappedEdges
        );
        String id = definitionService.createDefinition(req);
        log.info("Import 완료 — id={}, nm={} (원본 '{}', policy={})",
                id, effectiveName, payload.definitionNm(), policy);
        return new ImportResult(id, effectiveName, policy);
    }

    /**
     * 충돌 정책에 따라 최종 이름 결정.
     * <ul>
     *   <li>{@code NEW_VERSION} — 그대로 사용 (createDefinition이 자동 버전 bump)</li>
     *   <li>{@code RENAME} — 충돌 시 ``{이름} (1)``, ``(2)`` ... 식 suffix</li>
     *   <li>{@code REJECT} — 충돌 시 IllegalArgumentException</li>
     * </ul>
     */
    private String resolveName(String requested, ConflictPolicy policy) {
        return switch (policy) {
            case NEW_VERSION -> requested;
            case REJECT -> {
                if (definitionRepository.findMaxVersionByName(requested) > 0) {
                    throw new IllegalArgumentException("이미 존재하는 정의 이름: " + requested
                            + " (onConflict=reject)");
                }
                yield requested;
            }
            case RENAME -> {
                if (definitionRepository.findMaxVersionByName(requested) == 0) yield requested;
                for (int i = 1; i <= RENAME_MAX_ATTEMPTS; i++) {
                    String candidate = requested + " (" + i + ")";
                    if (definitionRepository.findMaxVersionByName(candidate) == 0) yield candidate;
                }
                throw new IllegalArgumentException("rename suffix 한계 도달 (" + RENAME_MAX_ATTEMPTS + "회) — " + requested);
            }
        };
    }

    /** 충돌 처리 정책. */
    public enum ConflictPolicy {
        /** 기존 이름 그대로 새 버전으로 추가 (createDefinition 기본 동작). */
        NEW_VERSION,
        /** 충돌 시 ``(1)``, ``(2)`` ... suffix로 새 이름 생성. */
        RENAME,
        /** 충돌 시 거부 — 4xx 응답. */
        REJECT;

        /** URL 쿼리(``newVersion``/``rename``/``reject``) → enum. 기본 NEW_VERSION. */
        public static ConflictPolicy fromQuery(String value) {
            if (value == null || value.isBlank()) return NEW_VERSION;
            return switch (value.trim().toLowerCase()) {
                case "newversion", "new_version" -> NEW_VERSION;
                case "rename" -> RENAME;
                case "reject" -> REJECT;
                default -> throw new IllegalArgumentException("알 수 없는 onConflict: " + value
                        + " (허용: newVersion / rename / reject)");
            };
        }
    }

    /** import 결과 — 생성 ID + 적용된 최종 이름 + 적용된 정책. */
    public record ImportResult(String definitionId, String definitionNm, ConflictPolicy appliedPolicy) {}
}
