package com.station8.app.definition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * DAG 정의 등록 요청 DTO.
 *
 * <p>REST 컨트롤러 {@code @Valid} 검증으로 1차 입력 검증, 그래프 위상 검증은
 * {@code DagValidator}가 별도로 수행. 검증 실패는 {@code GlobalRestExceptionHandler}가
 * {@code ErrorResponse(VALIDATION_FAILED)}로 변환.</p>
 *
 * <pre>{@code
 * {
 *   "definitionNm": "OrderFlow",
 *   "description": "주문 처리 파이프라인",
 *   "nodes": [
 *     {"nodeId": "n-validate", "nodeNm": "Validate", "activityNm": "VALIDATE_ORDER", "posX": 100, "posY": 100,
 *      "datasourceBindings": {"orders": "ops-mysql"}},
 *     {"nodeId": "n-charge",   "nodeNm": "Charge",   "activityNm": "CHARGE_PAYMENT", "posX": 300, "posY": 100}
 *   ],
 *   "edges": [
 *     {"edgeId": "e1", "fromNodeId": "n-validate", "toNodeId": "n-charge"}
 *   ]
 * }
 * }</pre>
 *
 * <p>모든 식별자(nodeId/edgeId)는 클라이언트가 발급한다 (UUID 권장). 서버는 DB에 그대로 저장한다.</p>
 *
 * <p>{@code datasourceBindings}(#113): 역(station)에서 사용할 DataSource 매핑 (role → registry 이름).
 * 액티비티가 {@code @BoundDataSource("role")}로 선언한 파라미터에 매핑된 풀이 주입된다. 미선언/누락 시
 * {@code primary} fallback.</p>
 *
 * @param definitionNm     라인 정의 이름. 필수, 1~100자. 같은 이름은 새 버전으로 자동 생성된다.
 * @param description      설명 (선택). 자유 텍스트, 길이 제한 없음.
 * @param slaSeconds       #138 — SLA 시간 임계치(초). null이면 SLA 비활성. 인스턴스 RUN_OPTIONS로 override 가능.
 * @param slaAction        #138 — SLA 위반 시 액션({@code ALERT_ONLY} / {@code AUTO_TERMINATE}). null이면 기본.
 * @param concurrencyPolicy #141 — 동시 실행 정책({@code CONCURRENT} 기본 / {@code SKIP_IF_RUNNING} / {@code PIPELINE_1/2/3}).
 * @param tags             #142 — 라인 정의 태그(free-form). null/empty면 태그 없음. 분류/필터 용도.
 * @param nodes            DAG 노드 목록. 필수, 최소 1개. 각 NodeDef는 {@link NodeDef} 검증.
 * @param edges            DAG 엣지 목록. null/empty 허용 (단일 노드 정의 케이스).
 */
public record DagDefinitionRequest(
        @NotBlank(message = "definitionNm은 필수입니다.")
        @Size(max = 100, message = "definitionNm은 100자를 초과할 수 없습니다.")
        String definitionNm,
        String description,
        Long slaSeconds,
        String slaAction,
        String concurrencyPolicy,
        List<String> tags,
        @NotEmpty(message = "nodes는 최소 1개 이상이어야 합니다.")
        @Valid
        List<NodeDef> nodes,
        @Valid
        List<EdgeDef> edges
) {
    /** 후방 호환 — SLA/concurrency/tags 없이 기존 4-arg 생성. */
    public DagDefinitionRequest(String definitionNm, String description,
                                List<NodeDef> nodes, List<EdgeDef> edges) {
        this(definitionNm, description, null, null, null, null, nodes, edges);
    }

    /** 후방 호환 — SLA만 받고 concurrency/tags 없이 (#138 시그니처). */
    public DagDefinitionRequest(String definitionNm, String description,
                                Long slaSeconds, String slaAction,
                                List<NodeDef> nodes, List<EdgeDef> edges) {
        this(definitionNm, description, slaSeconds, slaAction, null, null, nodes, edges);
    }

    /** 후방 호환 — SLA + concurrency 받고 tags 없이 (#141 시그니처). */
    public DagDefinitionRequest(String definitionNm, String description,
                                Long slaSeconds, String slaAction,
                                String concurrencyPolicy,
                                List<NodeDef> nodes, List<EdgeDef> edges) {
        this(definitionNm, description, slaSeconds, slaAction, concurrencyPolicy, null, nodes, edges);
    }

    /**
     * 노드 정의 — DAG의 활동 단위(역).
     *
     * @param nodeId             클라이언트가 발급한 외부 ID. 필수, 1~100자.
     * @param nodeNm             표시용 이름. 필수.
     * @param activityNm         활동 등록명 (LineRegistry에 등록된 이름과 일치). 필수.
     * @param inputParams        활동에 주입할 입력 파라미터 (JSON 또는 String). 선택.
     * @param posX               빌더 캔버스 x 좌표. 선택.
     * @param posY               빌더 캔버스 y 좌표. 선택.
     * @param datasourceBindings #113 — role → DataSource registry name. null/빈 맵이면 모든 binding은 primary fallback.
     */
    public record NodeDef(
            @NotBlank(message = "nodeId는 필수입니다.")
            @Size(max = 100, message = "nodeId는 100자를 초과할 수 없습니다.")
            String nodeId,
            @NotBlank(message = "nodeNm은 필수입니다.")
            String nodeNm,
            @NotBlank(message = "activityNm은 필수입니다.")
            String activityNm,
            String inputParams,
            Integer posX,
            Integer posY,
            Map<String, String> datasourceBindings
    ) {
    }

    /**
     * 엣지 정의 — 노드 간 의존성.
     *
     * @param edgeId        클라이언트가 발급한 외부 ID. 필수.
     * @param fromNodeId    선행 노드 ID. 필수.
     * @param toNodeId      후행 노드 ID. 필수.
     * @param conditionExpr SpEL 조건식 (#152). 빈 값이면 무조건 활성화.
     */
    public record EdgeDef(
            @NotBlank(message = "edgeId는 필수입니다.")
            String edgeId,
            @NotBlank(message = "fromNodeId는 필수입니다.")
            String fromNodeId,
            @NotBlank(message = "toNodeId는 필수입니다.")
            String toNodeId,
            String conditionExpr
    ) {
    }
}
