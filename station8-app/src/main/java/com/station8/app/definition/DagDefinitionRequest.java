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
    /**
     * 후방 호환 — SLA/concurrency/tags 없이 기존 4-arg 생성.
     *
     * @deprecated #178 — 신규 코드는 {@link #builder()}를 사용. 본 생성자는 이후 마일스톤에서 제거 예정.
     */
    @Deprecated(since = "0.0.1", forRemoval = true)
    public DagDefinitionRequest(String definitionNm, String description,
                                List<NodeDef> nodes, List<EdgeDef> edges) {
        this(definitionNm, description, null, null, null, null, nodes, edges);
    }

    /**
     * 후방 호환 — SLA만 받고 concurrency/tags 없이 (#138 시그니처).
     *
     * @deprecated #178 — 신규 코드는 {@link #builder()}를 사용. 본 생성자는 이후 마일스톤에서 제거 예정.
     */
    @Deprecated(since = "0.0.1", forRemoval = true)
    public DagDefinitionRequest(String definitionNm, String description,
                                Long slaSeconds, String slaAction,
                                List<NodeDef> nodes, List<EdgeDef> edges) {
        this(definitionNm, description, slaSeconds, slaAction, null, null, nodes, edges);
    }

    /**
     * 후방 호환 — SLA + concurrency 받고 tags 없이 (#141 시그니처).
     *
     * @deprecated #178 — 신규 코드는 {@link #builder()}를 사용. 본 생성자는 이후 마일스톤에서 제거 예정.
     */
    @Deprecated(since = "0.0.1", forRemoval = true)
    public DagDefinitionRequest(String definitionNm, String description,
                                Long slaSeconds, String slaAction,
                                String concurrencyPolicy,
                                List<NodeDef> nodes, List<EdgeDef> edges) {
        this(definitionNm, description, slaSeconds, slaAction, concurrencyPolicy, null, nodes, edges);
    }

    /**
     * #178 — 신규 빌더 진입점. 누적 추가되던 후방 호환 생성자(4/6/7-arg) 대신 사용.
     *
     * <p>OCP 충족 — 미래 설정 항목({@code workspaceId} #168, {@code folderPath} #169 등) 추가 시
     * {@link LineSettings} record와 본 Builder만 확장하면 되고, {@link DagDefinitionRequest}
     * canonical 8-arg 생성자는 손대지 않는다 (Builder.build()가 unpack해 호출).</p>
     *
     * @return 빈 빌더
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * #178 — 라인 정의의 설정값 묶음 (SLA / Concurrency / Tags).
     *
     * <p>{@link Builder}의 {@link Builder#settings(LineSettings)}로 일괄 주입하거나, 호출자가
     * 직접 만들어 다른 자리(예: import payload)에서 운반할 때 사용한다.</p>
     *
     * <p>미래 확장 후보 ({@code workspaceId}, {@code folderPath}, tag-prefix 규칙 등)는 본
     * record component에 추가하면 되며, 그때마다 {@link DagDefinitionRequest}의 canonical
     * 8-arg 생성자에 새 인자가 누적되는 것을 회피한다.</p>
     *
     * @param slaSeconds        SLA 시간 임계치(초). {@code null}이면 SLA 비활성.
     * @param slaAction         SLA 위반 시 액션 ({@code ALERT_ONLY} / {@code AUTO_TERMINATE}).
     * @param concurrencyPolicy 동시 실행 정책 ({@code CONCURRENT} / {@code SKIP_IF_RUNNING} / {@code PIPELINE_*}).
     * @param tags              free-form 태그 ({@code null}/빈 목록 허용).
     */
    public record LineSettings(
            Long slaSeconds,
            String slaAction,
            String concurrencyPolicy,
            List<String> tags
    ) {
        /** 모든 설정값을 {@code null}로 둔 default 인스턴스 — 정의의 default 동작을 의미. */
        public static LineSettings empty() {
            return new LineSettings(null, null, null, null);
        }
    }

    /**
     * #178 — {@link DagDefinitionRequest} 빌더. 누적되던 후방 호환 생성자를 대체한다.
     *
     * <p>사용 예:</p>
     * <pre>{@code
     * DagDefinitionRequest req = DagDefinitionRequest.builder()
     *     .definitionNm("OrderFlow")
     *     .description("주문 처리")
     *     .slaSeconds(3600L)
     *     .slaAction("ALERT_ONLY")
     *     .concurrencyPolicy("SKIP_IF_RUNNING")
     *     .tags(List.of("prod", "core"))
     *     .nodes(List.of(node))
     *     .edges(List.of())
     *     .build();
     * }</pre>
     *
     * <p>설정값을 묶음으로 주입하려면 {@link #settings(LineSettings)}를 사용. 설정값과 개별 setter
     * (예: {@link #slaSeconds(Long)})를 혼용한 경우 <b>가장 마지막에 호출된 값</b>이 이긴다.</p>
     */
    public static final class Builder {
        private String definitionNm;
        private String description;
        private Long slaSeconds;
        private String slaAction;
        private String concurrencyPolicy;
        private List<String> tags;
        private List<NodeDef> nodes;
        private List<EdgeDef> edges;

        /** 패키지 외부에서는 {@link DagDefinitionRequest#builder()}로 진입. */
        private Builder() {
        }

        /** @param v 라인 정의 이름. 필수, 1~100자. */
        public Builder definitionNm(String v) {
            this.definitionNm = v;
            return this;
        }

        /** @param v 설명. 선택, 자유 텍스트. */
        public Builder description(String v) {
            this.description = v;
            return this;
        }

        /**
         * SLA + Concurrency + Tags를 묶음으로 일괄 설정. {@code null} 입력은 no-op.
         *
         * @param s 설정값 묶음 ({@code null} 허용 → 아무것도 안 함)
         */
        public Builder settings(LineSettings s) {
            if (s == null) {
                return this;
            }
            this.slaSeconds = s.slaSeconds();
            this.slaAction = s.slaAction();
            this.concurrencyPolicy = s.concurrencyPolicy();
            this.tags = s.tags();
            return this;
        }

        /** @param v SLA 시간 임계치(초). {@code null}이면 SLA 비활성. */
        public Builder slaSeconds(Long v) {
            this.slaSeconds = v;
            return this;
        }

        /** @param v SLA 액션 ({@code ALERT_ONLY} / {@code AUTO_TERMINATE}). */
        public Builder slaAction(String v) {
            this.slaAction = v;
            return this;
        }

        /** @param v 동시 실행 정책 enum 이름. */
        public Builder concurrencyPolicy(String v) {
            this.concurrencyPolicy = v;
            return this;
        }

        /** @param v 태그 목록 ({@code null}/빈 목록 허용). */
        public Builder tags(List<String> v) {
            this.tags = v;
            return this;
        }

        /** @param v 노드 목록. 빌드 시점에 비어있으면 호출 측의 {@code @Valid} 또는 서비스 레이어가 검증. */
        public Builder nodes(List<NodeDef> v) {
            this.nodes = v;
            return this;
        }

        /** @param v 엣지 목록 ({@code null}/빈 목록 허용 — 단일 노드 정의). */
        public Builder edges(List<EdgeDef> v) {
            this.edges = v;
            return this;
        }

        /**
         * 누적된 값으로 {@link DagDefinitionRequest} 인스턴스를 생성. canonical 8-arg 생성자에 unpack.
         *
         * <p>Bean Validation은 컨트롤러의 {@code @Valid}에서 수행 — 빌더 자체는 검증하지 않는다
         * (테스트가 일부러 부분 객체를 만드는 케이스를 허용).</p>
         *
         * @return 새 요청 인스턴스
         */
        public DagDefinitionRequest build() {
            return new DagDefinitionRequest(
                    definitionNm, description,
                    slaSeconds, slaAction, concurrencyPolicy, tags,
                    nodes, edges
            );
        }
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
