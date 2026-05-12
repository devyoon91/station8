package com.station8.app.definition;

import java.time.Instant;
import java.util.List;

/**
 * #193 — DAG 정의 export 포맷.
 *
 * <p>{@link DagDefinitionResponse}를 export-friendly 형태로 래핑. 사이트 간 이식 시
 * 서버 내부 식별자({@code definitionId})는 제외하고 사용자가 정한 식별자({@code definitionNm},
 * {@code nodeId}, {@code edgeId})만 유지한다. import 시엔 {@code definitionId}는 새로 발급.</p>
 *
 * <h3>호환성</h3>
 * <ul>
 *   <li>{@code schemaVersion}이 최상위에 명시되어 향후 포맷 변경 시 호환 검증 가능.</li>
 *   <li>{@code exportedAt}은 정보 용도 (import 시 무시).</li>
 *   <li>{@code versionNo}는 사람을 위한 표시 메타 (import 시 무시 — 항상 새 정의로 생성).</li>
 * </ul>
 *
 * <h3>제외 (out of scope)</h3>
 * <ul>
 *   <li>인스턴스 실행 이력 / DLQ.</li>
 *   <li>연결된 스케줄({@code U_LINE_SCHEDULE}).</li>
 *   <li>ACL grant — import 환경의 권한 정책에 맡김.</li>
 * </ul>
 */
public record DefinitionExportPayload(
        String schemaVersion,
        String exportedAt,
        String definitionNm,
        String description,
        /** info-only — import 시 무시. */
        Integer versionNo,
        Long slaSeconds,
        String slaAction,
        String concurrencyPolicy,
        List<String> tags,
        List<DagDefinitionRequest.NodeDef> nodes,
        List<DagDefinitionRequest.EdgeDef> edges
) {

    /** 현재 포맷 버전. 향후 incompatible 변경 시 "2" 등으로 bump. */
    public static final String CURRENT_SCHEMA_VERSION = "1";

    /** {@link DagDefinitionResponse}에서 export payload로 변환. */
    public static DefinitionExportPayload from(DagDefinitionResponse def) {
        return new DefinitionExportPayload(
                CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                def.definitionNm(),
                def.description(),
                def.versionNo(),
                def.slaSeconds(),
                def.slaAction(),
                def.concurrencyPolicy(),
                def.tags(),
                def.nodes(),
                def.edges()
        );
    }
}
