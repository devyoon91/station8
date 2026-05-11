package com.station8.engine.core;

import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineStation;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.InstanceQueryFilter;
import com.station8.engine.repository.LineDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * #164 — Pipeline 1/2/3 모드 게이트.
 *
 * <p>Worker가 PENDING 활동을 RUNNING으로 dispatch하기 직전에 호출. 정의의 ConcurrencyPolicy가
 * PIPELINE_*인 경우 선행 인스턴스 진행도와 비교해 통과 가능 여부를 판단.</p>
 *
 * <h3>알고리즘</h3>
 * <ol>
 *   <li>정의의 정책이 PIPELINE_K(K=1,2,3)이 아니면 통과 (false=차단 안 함).</li>
 *   <li>활동에 nodeId가 없으면(legacy) 통과.</li>
 *   <li>활동의 위상 단계 S 계산. step 미해석(사이클·고립)이면 통과.</li>
 *   <li>같은 workflow_name의 RUNNING 인스턴스 중 자기 자신을 제외한 선행 후보 조회. 0건이면 통과.</li>
 *   <li>각 선행에 대해 게이트 검사 — 어느 하나라도 게이트 미충족이면 차단.</li>
 * </ol>
 *
 * <p>본 게이트는 호출자(LineWorker)가 dispatch 전에 호출하므로 락/트랜잭션은 별도. 차단 시 호출자가
 * {@link ActivityRepository#revertGateBlocked} 호출로 PENDING 복구 + NEXT_RETRY_DT 지연.</p>
 */
@Component
public class PipelineGate {

    private static final Logger log = LoggerFactory.getLogger(PipelineGate.class);

    /** 차단 시 다음 폴링까지 지연 — 핫 루프 방지. */
    public static final java.time.Duration GATE_BACKOFF = java.time.Duration.ofSeconds(2);

    private final LineDefinitionRepository definitionRepo;
    private final ActivityRepository activityRepo;

    public PipelineGate(LineDefinitionRepository definitionRepo, ActivityRepository activityRepo) {
        this.definitionRepo = definitionRepo;
        this.activityRepo = activityRepo;
    }

    /**
     * 활동 dispatch 가능 여부를 판단. #177 — {@link ConcurrencyStrategy}에 위임.
     * 본 클래스는 dispatch context 빌더 + 위상/선행 lookup만 담당.
     *
     * @return true = dispatch OK. false = 게이트로 차단.
     */
    public boolean canDispatch(String instanceId, String nodeId, String workflowName) {
        if (workflowName == null || nodeId == null) return true;

        // 정책 lookup — workflowName으로 active 정의 조회
        LineDefinition def = definitionRepo.findActiveDefinitionByName(workflowName);
        if (def == null) return true;

        ConcurrencyStrategy strategy = ConcurrencyStrategy.parse(def.concurrencyPolicy());
        // Pipeline 외 정책은 default no-op으로 통과 — DispatchContext 빌드 비용 절감을 위해 짧은 경로 유지
        if (!(strategy instanceof ConcurrencyStrategy.Pipeline)) return true;

        // 위상 단계 계산
        List<LineStation> nodes = definitionRepo.findNodesByDefinition(def.id());
        List<LineTrack> edges = definitionRepo.findEdgesByDefinition(def.id());
        Map<String, Integer> stepMap = LineDagTopo.computeStepLayers(nodes, edges);
        Integer myStep = stepMap.get(nodeId);
        if (myStep == null) {
            // 사이클 안 있는 노드 / unreachable — 안전 통과
            return true;
        }

        // 선행 후보: 같은 workflow_name + RUNNING + 자기 자신 제외
        // (PAUSED는 제외 — 운영자 결정 대기, 데드락 회피)
        InstanceQueryFilter filter = new InstanceQueryFilter(
                workflowName, java.util.List.of("RUNNING"), null, null, null, null, null, null);
        List<LineInstance> candidates = activityRepo.findInstancesPage(filter, 0, 200);
        List<String> priorIds = candidates.stream()
                .map(LineInstance::id)
                .filter(id -> !instanceId.equals(id))
                .toList();

        ConcurrencyStrategy.DispatchContext ctx = new ConcurrencyStrategy.DispatchContext(
                instanceId, nodeId, workflowName, myStep,
                step -> LineDagTopo.nodesAtStep(stepMap, step),
                priorIds,
                activityRepo::isNodeCompleted,
                activityRepo::isAnyNodeStarted
        );

        ConcurrencyStrategy.DispatchResult result = strategy.evaluateOnDispatch(ctx);
        if (!result.allowed() && log.isDebugEnabled()) {
            log.debug("Dispatch 차단 — instance={}, nodeId={}, step={}, policy={}, reason={}",
                    instanceId, nodeId, myStep, strategy.policyName(), result.reason());
        }
        return result.allowed();
    }
}
