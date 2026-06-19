package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineStation;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * DAG 인터프리터: 라인 인스턴스를 시작하거나, 역 완료 시 후행 역을 활성화한다.
 *
 * 상태 머신:
 *   WAITING_DEPENDENCIES  --(모든 선행 COMPLETED)-->  PENDING
 *   PENDING               --(워커 폴링)-->            RUNNING
 *   RUNNING               --(성공)-->                COMPLETED
 *   RUNNING               --(실패+재시도여유)-->      PENDING(NEXT_RETRY_DT 세팅)
 *   RUNNING               --(실패+재시도소진)-->      FAILED
 *
 * fan-out: 역 N의 outgoing edges 후행들을 모두 PENDING으로 promote 시도.
 * fan-in:  후행 역의 incoming edges 모든 선행이 COMPLETED 일 때만 PENDING으로 promote.
 *
 * 동시성: ``promoteToPending``은 ``WHERE STATUS_ST='WAITING_DEPENDENCIES'`` 조건부 UPDATE이므로
 * 두 선행이 거의 동시에 완료되어도 단 한 번만 PENDING으로 전이된다.
 */
@Component
public class DagInterpreter {

    private static final Logger log = LoggerFactory.getLogger(DagInterpreter.class);

    public static final String STATUS_WAITING = "WAITING_DEPENDENCIES";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private final LineDefinitionRepository definitionRepository;
    private final ActivityRepository activityRepository;
    private final DagValidator dagValidator;
    private final LineRegistry workflowRegistry;
    private final EdgeConditionEvaluator conditionEvaluator;
    private final LineExecutor lineExecutor;
    private final JsonUtil jsonUtil;

    public DagInterpreter(LineDefinitionRepository definitionRepository,
                          ActivityRepository activityRepository,
                          DagValidator dagValidator,
                          LineRegistry workflowRegistry,
                          EdgeConditionEvaluator conditionEvaluator,
                          @Lazy LineExecutor lineExecutor,
                          JsonUtil jsonUtil) {
        this.definitionRepository = definitionRepository;
        this.activityRepository = activityRepository;
        this.dagValidator = dagValidator;
        this.workflowRegistry = workflowRegistry;
        this.conditionEvaluator = conditionEvaluator;
        this.lineExecutor = lineExecutor;
        this.jsonUtil = jsonUtil;
    }

    /**
     * DAG 정의로부터 새 인스턴스를 시작한다.
     * 모든 역의 ActivityExecution을 생성하되, 시작 역만 PENDING이고 그 외는 WAITING_DEPENDENCIES.
     *
     * @param definitionId DAG 정의 ID
     * @param instanceId 호출자가 발급한 인스턴스 ID (U_LINE_INSTANCE에 이미 INSERT 되어 있어야 함)
     * @param inputData 인스턴스 입력 (각 역의 INPUT_PARAMS와 별개; 시작 역에 함께 주입됨)
     */
    @Transactional
    public void startInstance(String definitionId, String instanceId, String inputData) {
        List<LineStation> nodes = definitionRepository.findNodesByDefinition(definitionId);
        List<LineTrack> edges = definitionRepository.findEdgesByDefinition(definitionId);

        // 실행 직전 안전망 검증 (정의 저장 시점에 이미 검증되어야 하지만 이중 방어)
        dagValidator.validate(nodes, edges, workflowRegistry.getActivityNames());

        List<LineStation> startNodes = definitionRepository.findStartNodes(definitionId);
        if (startNodes.isEmpty()) {
            // dagValidator.validate에서 DAG_NO_START_NODE를 이미 잡지만, 정의/엣지 데이터 정합성 비상 가드
            throw new IllegalStateException("DAG 정의에 시작 역(incoming edge 0개)가 없습니다: definitionId=" + definitionId);
        }

        for (LineStation node : nodes) {
            boolean isStart = startNodes.stream().anyMatch(s -> s.id().equals(node.id()));
            String status = isStart ? STATUS_PENDING : STATUS_WAITING;
            String nodeInput = isStart ? mergeInput(node.inputParams(), inputData) : node.inputParams();
            activityRepository.createForNode(instanceId, node.id(), node.activityNm(), status, nodeInput);
        }
        log.info("DAG instance started: instanceId={}, definitionId={}, nodeCount={}, startNodeCount={}",
                instanceId, definitionId, nodes.size(), startNodes.size());
    }

    /**
     * 역 완료 통지. 후행 역들의 fan-in 조건을 점검하고, 모두 만족하면 PENDING으로 promote.
     *
     * <p>#152 — 엣지에 {@code conditionExpr}이 있으면 SpEL 평가 후 만족하는 엣지만 활성화.
     * 활성화된 엣지가 0건이면 인스턴스를 FAILED로 마킹 (조건 미달).
     * 평가 도중 예외가 발생하면 표현식 오류로 인스턴스 FAILED.</p>
     *
     * @param instanceId 인스턴스 ID
     * @param completedNodeId 방금 COMPLETED 된 역 ID
     */
    @Transactional
    public void onNodeCompleted(String instanceId, String completedNodeId) {
        // #101 — 인스턴스가 TERMINATED/FAILED면 fan-out 차단
        LineInstance instance = activityRepository.findInstanceById(instanceId);
        if (instance != null && !"RUNNING".equals(instance.statusSt())) {
            log.info("Instance is {} — fan-out blocked: instanceId={}, completedNodeId={}",
                    instance.statusSt(), instanceId, completedNodeId);
            return;
        }

        // #364 — nodeId가 정의 간 충돌할 수 있으므로 정의 테이블 조회는 definitionId 스코프 필수.
        // 인스턴스에서 소속 정의를 얻는다(레거시/마이그레이션 전 in-flight 인스턴스는 null일 수 있음 → fan-out skip).
        String definitionId = instance != null ? instance.definitionId() : null;
        if (definitionId == null) {
            log.warn("definitionId 미상 — fan-out 평가 skip: instanceId={}, completedNodeId={}",
                    instanceId, completedNodeId);
            return;
        }

        List<LineTrack> outgoing = definitionRepository.findOutgoingEdges(definitionId, completedNodeId);
        if (outgoing.isEmpty()) {
            log.debug("Terminal node completed: instanceId={}, nodeId={}", instanceId, completedNodeId);
            return;
        }

        // #152 — 완료된 활동의 결과를 조건 평가의 #result로 사용
        ActivityExecution completedExec = activityRepository.findByInstanceAndNode(instanceId, completedNodeId);
        String resultJson = completedExec != null ? completedExec.outputData() : null;

        // 조건 평가 — 만족하는 엣지만 활성화 후보로 수집 (D3=a)
        List<LineTrack> activatedEdges = new ArrayList<>();
        List<String> failedConditions = new ArrayList<>();
        for (LineTrack edge : outgoing) {
            try {
                if (conditionEvaluator.evaluate(edge.conditionExpr(), resultJson)) {
                    activatedEdges.add(edge);
                } else if (edge.conditionExpr() != null && !edge.conditionExpr().isBlank()) {
                    failedConditions.add(edge.fromNodeId() + " → " + edge.toNodeId()
                            + " [" + edge.conditionExpr() + "]");
                } else {
                    // 조건 없는데 false인 케이스는 없음 (evaluate는 null/blank이면 true 반환).
                    // 이 분기는 도달 안 함 — defensive로 활성화 처리.
                    activatedEdges.add(edge);
                }
            } catch (EdgeConditionEvaluator.ConditionEvaluationException ex) {
                // D5=b — 조건 평가 예외는 인스턴스 FAILED 사유로 명시
                String reason = "Edge condition evaluation failed at " + completedNodeId
                        + " → " + edge.toNodeId() + ": " + ex.getMessage();
                log.error("{}", reason, ex);
                lineExecutor.failLine(instanceId, reason);
                return;
            }
        }

        // D4=a — 활성화된 엣지가 0건이면 인스턴스 FAILED. 활동 자체는 COMPLETED 그대로.
        if (activatedEdges.isEmpty()) {
            String reason = "All outgoing edges from " + completedNodeId
                    + " failed condition: " + String.join("; ", failedConditions);
            log.warn("Instance blocked — no edge satisfied: instanceId={}, {}",
                    instanceId, reason);
            lineExecutor.failLine(instanceId, reason);
            return;
        }

        // 활성화된 엣지의 후행 노드 promote — fan-in 조건은 기존 그대로
        for (LineTrack edge : activatedEdges) {
            String successorId = edge.toNodeId();
            if (!allPredecessorsCompleted(definitionId, instanceId, successorId)) {
                continue;
            }
            // M22 (#369) — 후행이 FAN_OUT이면 선행 배열을 item당 행으로 materialize.
            // 그 외(NONE/COLLECT)는 기존 단일 행 promote 경로를 그대로 탄다.
            LineStation successor = definitionRepository.findStationById(definitionId, successorId);
            String mode = (successor == null) ? LineStation.STREAM_NONE : successor.streamModeOrDefault();
            if (LineStation.STREAM_FAN_OUT.equals(mode)) {
                materializeFanOut(instanceId, successorId, edge.fromNodeId(), successor);
            } else {
                promoteSingle(instanceId, successorId);
            }
        }
    }

    /** 기존 단일 행 promote — NONE/COLLECT 후행. 동작은 M22 이전과 동일. */
    private void promoteSingle(String instanceId, String successorId) {
        ActivityExecution successorExec = activityRepository.findByInstanceAndNode(instanceId, successorId);
        if (successorExec == null) {
            log.warn("Successor execution not found: instanceId={}, nodeId={}", instanceId, successorId);
            return;
        }
        if (STATUS_WAITING.equals(successorExec.statusSt())) {
            activityRepository.promoteToPending(successorExec.id());
            log.info("Promoted to PENDING: instanceId={}, nodeId={}, executionId={}",
                    instanceId, successorId, successorExec.id());
        }
    }

    /**
     * M22 (#369) fan-out materialize — 선행 노드 출력 배열을 후행 FAN_OUT 노드의 item당 행으로 펼친다.
     *
     * <p>후행은 시작 시 단일 WAITING 행이 미리 생성돼 있다(item 0). 선행 출력이 길이 K 배열이면
     * 그 행을 PENDING으로 promote(item 0)하고 item 1..K-1 행을 추가 생성한다. 모두 PENDING이라
     * 워커가 병렬로 집어간다. 비-배열 출력은 length-1 degenerate로 단일 promote.</p>
     *
     * <p>idempotent — 단일 WAITING 행이 이미 사라졌으면(materialize 완료) 아무것도 안 한다.</p>
     */
    private void materializeFanOut(String instanceId, String successorId, String predNodeId, LineStation successor) {
        List<ActivityExecution> existing = activityRepository.findAllByInstanceAndNode(instanceId, successorId);
        ActivityExecution seed = existing.stream()
                .filter(r -> STATUS_WAITING.equals(r.statusSt()) && r.itemIndex() == 0)
                .findFirst().orElse(null);
        if (seed == null) {
            // 이미 materialize됨 — 중복 fan-out 방지
            return;
        }

        List<?> items = parseArrayOutput(instanceId, predNodeId);
        if (items == null) {
            // 비-배열 출력(단일 객체) → length-1 degenerate. 기존처럼 단일 promote.
            activityRepository.promoteToPending(seed.id());
            return;
        }
        int k = items.size();
        if (k == 0) {
            // 빈 배열 — 실행할 item이 없다. 레인을 빈 채로 COMPLETED 처리 후 후행 cascade.
            // (v1: 빈 fan-out은 no-op 통과. 부분실패/빈배열 정밀 정책은 후속.)
            activityRepository.updateStatus(seed.withStatus(STATUS_COMPLETED));
            log.info("Fan-out empty array — lane completed as no-op: instanceId={}, nodeId={}", instanceId, successorId);
            onNodeCompleted(instanceId, successorId);
            return;
        }
        // item 0 = seed 행, item 1..K-1 = 신규 행
        activityRepository.promoteToPending(seed.id());
        for (int i = 1; i < k; i++) {
            activityRepository.createForNodeItem(instanceId, successorId, successor.activityNm(),
                    STATUS_PENDING, successor.inputParams(), i);
        }
        log.info("Fan-out materialized: instanceId={}, nodeId={}, items={}", instanceId, successorId, k);
    }

    /** 선행 노드 출력을 배열로 파싱. 배열이 아니거나 파싱 실패면 null. */
    private List<?> parseArrayOutput(String instanceId, String predNodeId) {
        ActivityExecution predExec = activityRepository.findByInstanceAndNode(instanceId, predNodeId);
        String json = predExec != null ? predExec.outputData() : null;
        if (json == null || json.isBlank()) return null;
        try {
            Object parsed = jsonUtil.fromJson(json, Object.class);
            return (parsed instanceof List<?> list) ? list : null;
        } catch (Exception ex) {
            log.warn("Fan-out 선행 출력 배열 파싱 실패 — predNodeId={}: {}", predNodeId, ex.getMessage());
            return null;
        }
    }

    private boolean allPredecessorsCompleted(String definitionId, String instanceId, String nodeId) {
        List<LineTrack> incoming = definitionRepository.findIncomingEdges(definitionId, nodeId);
        for (LineTrack edge : incoming) {
            String predId = edge.fromNodeId();
            LineStation pred = definitionRepository.findStationById(definitionId, predId);
            boolean predIsFanOut = pred != null && LineStation.STREAM_FAN_OUT.equals(pred.streamModeOrDefault());
            if (predIsFanOut) {
                // M22 — fan-out 선행: 모든 item 레인이 완료돼야 한다 (레인별 최소 1개 COMPLETED + 미완 행 없음).
                if (!fanOutLaneAllCompleted(instanceId, predId)) {
                    return false;
                }
            } else {
                // NONE/비-fan-out 선행: 기존 단일 행 판정 그대로.
                ActivityExecution predExec = activityRepository.findByInstanceAndNode(instanceId, predId);
                if (predExec == null || !STATUS_COMPLETED.equals(predExec.statusSt())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * fan-out 선행 노드의 모든 item 레인이 완료됐는지. 레인(itemIndex)별로 그룹핑해,
     * 각 레인에 COMPLETED 행이 있고 미완(PENDING/RUNNING/WAITING) 행이 없어야 한다.
     * retry로 한 레인에 FAILED+COMPLETED가 공존할 수 있어 레인 단위로 본다.
     */
    private boolean fanOutLaneAllCompleted(String instanceId, String predId) {
        List<ActivityExecution> rows = activityRepository.findAllByInstanceAndNode(instanceId, predId);
        if (rows.isEmpty()) return false;
        java.util.Map<Integer, List<ActivityExecution>> byLane = new java.util.HashMap<>();
        for (ActivityExecution r : rows) {
            byLane.computeIfAbsent(r.itemIndex(), x -> new ArrayList<>()).add(r);
        }
        for (List<ActivityExecution> lane : byLane.values()) {
            boolean anyCompleted = lane.stream().anyMatch(r -> STATUS_COMPLETED.equals(r.statusSt()));
            boolean anyInFlight = lane.stream().anyMatch(r ->
                    STATUS_PENDING.equals(r.statusSt()) || "RUNNING".equals(r.statusSt())
                            || STATUS_WAITING.equals(r.statusSt()));
            if (!anyCompleted || anyInFlight) {
                return false;
            }
        }
        return true;
    }

    /**
     * 역의 정적 INPUT_PARAMS와 인스턴스 단위 입력을 합성한다.
     * 현재 v1: 인스턴스 입력이 우선, 없으면 역 INPUT_PARAMS. (각 역이 DB 직접 R/W 전제이므로 단순)
     * 향후 JSON merge / placeholder 치환은 별도 이슈에서 다룬다.
     */
    private String mergeInput(String nodeInput, String instanceInput) {
        if (instanceInput != null && !instanceInput.isBlank()) return instanceInput;
        return nodeInput;
    }
}
