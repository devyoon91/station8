package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.entity.WorkflowEdge;
import com.bangrang.workflow.engine.entity.WorkflowNode;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.repository.WorkflowDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DAG 인터프리터: 워크플로우 인스턴스를 시작하거나, 노드 완료 시 후행 노드를 활성화한다.
 *
 * 상태 머신:
 *   WAITING_DEPENDENCIES  --(모든 선행 COMPLETED)-->  PENDING
 *   PENDING               --(워커 폴링)-->            RUNNING
 *   RUNNING               --(성공)-->                COMPLETED
 *   RUNNING               --(실패+재시도여유)-->      PENDING(NEXT_RETRY_DT 세팅)
 *   RUNNING               --(실패+재시도소진)-->      FAILED
 *
 * fan-out: 노드 N의 outgoing edges 후행들을 모두 PENDING으로 promote 시도.
 * fan-in:  후행 노드의 incoming edges 모든 선행이 COMPLETED 일 때만 PENDING으로 promote.
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

    private final WorkflowDefinitionRepository definitionRepository;
    private final ActivityRepository activityRepository;

    public DagInterpreter(WorkflowDefinitionRepository definitionRepository,
                          ActivityRepository activityRepository) {
        this.definitionRepository = definitionRepository;
        this.activityRepository = activityRepository;
    }

    /**
     * DAG 정의로부터 새 인스턴스를 시작한다.
     * 모든 노드의 ActivityExecution을 생성하되, 시작 노드만 PENDING이고 그 외는 WAITING_DEPENDENCIES.
     *
     * @param definitionId DAG 정의 ID
     * @param instanceId 호출자가 발급한 인스턴스 ID (U_WF_INSTANCE에 이미 INSERT 되어 있어야 함)
     * @param inputData 인스턴스 입력 (각 노드의 INPUT_PARAMS와 별개; 시작 노드에 함께 주입됨)
     */
    @Transactional
    public void startInstance(String definitionId, String instanceId, String inputData) {
        List<WorkflowNode> nodes = definitionRepository.findNodesByDefinition(definitionId);
        if (nodes.isEmpty()) {
            throw new IllegalStateException("DAG 정의에 노드가 없습니다: definitionId=" + definitionId);
        }
        List<WorkflowNode> startNodes = definitionRepository.findStartNodes(definitionId);
        if (startNodes.isEmpty()) {
            throw new IllegalStateException("DAG 정의에 시작 노드(incoming edge 0개)가 없습니다: definitionId=" + definitionId);
        }

        for (WorkflowNode node : nodes) {
            boolean isStart = startNodes.stream().anyMatch(s -> s.id().equals(node.id()));
            String status = isStart ? STATUS_PENDING : STATUS_WAITING;
            String nodeInput = isStart ? mergeInput(node.inputParams(), inputData) : node.inputParams();
            activityRepository.createForNode(instanceId, node.id(), node.activityNm(), status, nodeInput);
        }
        log.info("DAG instance started: instanceId={}, definitionId={}, nodeCount={}, startNodeCount={}",
                instanceId, definitionId, nodes.size(), startNodes.size());
    }

    /**
     * 노드 완료 통지. 후행 노드들의 fan-in 조건을 점검하고, 모두 만족하면 PENDING으로 promote.
     *
     * @param instanceId 인스턴스 ID
     * @param completedNodeId 방금 COMPLETED 된 노드 ID
     */
    @Transactional
    public void onNodeCompleted(String instanceId, String completedNodeId) {
        List<WorkflowEdge> outgoing = definitionRepository.findOutgoingEdges(completedNodeId);
        if (outgoing.isEmpty()) {
            log.debug("Terminal node completed: instanceId={}, nodeId={}", instanceId, completedNodeId);
            return;
        }

        for (WorkflowEdge edge : outgoing) {
            String successorId = edge.toNodeId();
            if (allPredecessorsCompleted(instanceId, successorId)) {
                ActivityExecution successorExec = activityRepository.findByInstanceAndNode(instanceId, successorId);
                if (successorExec == null) {
                    log.warn("Successor execution not found: instanceId={}, nodeId={}", instanceId, successorId);
                    continue;
                }
                if (STATUS_WAITING.equals(successorExec.statusSt())) {
                    activityRepository.promoteToPending(successorExec.id());
                    log.info("Promoted to PENDING: instanceId={}, nodeId={}, executionId={}",
                            instanceId, successorId, successorExec.id());
                }
            }
        }
    }

    private boolean allPredecessorsCompleted(String instanceId, String nodeId) {
        List<WorkflowEdge> incoming = definitionRepository.findIncomingEdges(nodeId);
        for (WorkflowEdge edge : incoming) {
            ActivityExecution predExec = activityRepository.findByInstanceAndNode(instanceId, edge.fromNodeId());
            if (predExec == null || !STATUS_COMPLETED.equals(predExec.statusSt())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 노드의 정적 INPUT_PARAMS와 인스턴스 단위 입력을 합성한다.
     * 현재 v1: 인스턴스 입력이 우선, 없으면 노드 INPUT_PARAMS. (각 노드가 DB 직접 R/W 전제이므로 단순)
     * 향후 JSON merge / placeholder 치환은 별도 이슈에서 다룬다.
     */
    private String mergeInput(String nodeInput, String instanceInput) {
        if (instanceInput != null && !instanceInput.isBlank()) return instanceInput;
        return nodeInput;
    }
}
