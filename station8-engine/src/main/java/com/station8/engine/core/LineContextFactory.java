package com.station8.engine.core;

import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * #146 — {@link DefaultLineContext} 조립 책임을 담당하는 sub-service.
 *
 * <p>{@link LineWorker}와 {@link ActivityProcessor}가 활동 실행 직전 필요한
 * {@link LineContext} + {@link RunOptions}을 한 번에 얻을 수 있도록 정리한다.
 * 인스턴스 메타 조회 실패 / RunOptions CLOB 파싱 실패는 모두 default로 안전 fallback —
 * 활동 실행 자체는 멈추지 않는다.</p>
 *
 * <h3>책임 (이전 위치)</h3>
 * <ul>
 *   <li>인스턴스 row 조회 (이전 {@code LineWorker.loadInstanceSafely})</li>
 *   <li>{@code U_LINE_INSTANCE.RUN_OPTIONS} CLOB 파싱 ({@link RunOptionsCodec}에 위임)</li>
 *   <li>{@link DefaultLineContext} 인스턴스 생성 + executionId 속성 + runtime params 주입</li>
 * </ul>
 */
@Component
public class LineContextFactory {

    private static final Logger log = LoggerFactory.getLogger(LineContextFactory.class);

    /** 인스턴스 조회 실패 / null instance인 경우 사용하는 workflowName fallback. */
    static final String UNKNOWN_WORKFLOW_NAME = "UNKNOWN";

    private final ActivityRepository activityRepository;
    private final JsonUtil jsonUtil;
    private final RunOptionsCodec runOptionsCodec;
    private final LineDefinitionRepository definitionRepository;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param activityRepository   인스턴스 메타 조회 + 직전 활동 output 조회 (#267)
     * @param jsonUtil             {@link DefaultLineContext}가 내부에 보유하는 JSON 유틸
     * @param runOptionsCodec      RUN_OPTIONS CLOB ↔ {@link RunOptions} 변환
     * @param definitionRepository 직전 노드 식별을 위한 incoming edge 조회 (#267 — M16 {@code $prev} 활성화)
     */
    public LineContextFactory(ActivityRepository activityRepository,
                              JsonUtil jsonUtil,
                              RunOptionsCodec runOptionsCodec,
                              LineDefinitionRepository definitionRepository) {
        this.activityRepository = activityRepository;
        this.jsonUtil = jsonUtil;
        this.runOptionsCodec = runOptionsCodec;
        this.definitionRepository = definitionRepository;
    }

    /**
     * 활동 실행 직전에 필요한 {@link DefaultLineContext} + {@link RunOptions} 묶음 생성.
     *
     * <p>인스턴스 조회 실패 / RunOptions 파싱 실패 모두 default fallback이라 본 메서드는
     * 정상 흐름에서 예외를 던지지 않는다 — 활동 실행 자체가 컨텍스트 조립 오류 때문에
     * 중단되는 일이 없도록 한다.</p>
     *
     * @param activity 실행 대상 활동 (instanceId / activityName / inputData / retryCnt 사용)
     * @return 활동 실행에 필요한 컨텍스트 + 옵션 묶음
     */
    public Bundle create(ActivityExecution activity) {
        LineInstance instance = loadInstanceSafely(activity.instanceId());
        // RunOptionsCodec이 단일 진입점 — null instance / 파싱 실패 모두 default로 안전 처리
        RunOptions options = runOptionsCodec.parseFromClob(
                instance == null ? null : instance.runOptions());

        String workflowName = (instance != null && instance.workflowName() != null)
                ? instance.workflowName()
                : UNKNOWN_WORKFLOW_NAME;

        DefaultLineContext context = new DefaultLineContext(
                activity.instanceId(),
                workflowName,
                activity.activityName(),
                activity.retryCnt() + 1,
                activity.inputData(),
                loadPreviousOutput(activity), // #267 — M16 $prev.json.* 활성
                jsonUtil
        );
        context.attributes().put("executionId", activity.id());
        context.setRuntimeParams(options.runtimeParams());
        return new Bundle(context, options);
    }

    /**
     * #267 — DAG 모드에서 직전 노드의 output을 로딩해 {@code $prev.json.*} 표현식이
     * 실제 값을 반환하도록 한다.
     *
     * <ul>
     *   <li>{@code activity.nodeId() == null} (linear/legacy): null</li>
     *   <li>incoming edge 0건 (start 노드): null</li>
     *   <li>incoming edge 1건 (선형 체인): predecessor의 OUTPUT_DATA</li>
     *   <li>incoming edge 2건 이상 (fan-in): null — 모호하므로 안전하게 미노출. 사용자는 {@code $ctx.input}의 정의된 {@code inputParams}를 그대로 사용</li>
     *   <li>조회 예외: null + WARN — 활동 실행 자체는 멈추지 않음</li>
     * </ul>
     */
    private Object loadPreviousOutput(ActivityExecution activity) {
        if (activity.nodeId() == null) return null;
        try {
            List<LineTrack> incoming = definitionRepository.findIncomingEdges(activity.nodeId());
            if (incoming.size() != 1) return null;
            String prevNodeId = incoming.get(0).fromNodeId();
            ActivityExecution prev = activityRepository.findByInstanceAndNode(activity.instanceId(), prevNodeId);
            return prev == null ? null : prev.outputData();
        } catch (Exception ex) {
            log.warn("$prev 직전 활동 output 조회 실패 — activityId={}, nodeId={}: {}",
                    activity.id(), activity.nodeId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 인스턴스 메타 안전 조회 — {@link EmptyResultDataAccessException} / 기타 예외 모두 null fallback.
     *
     * @param instanceId 조회 대상 인스턴스 ID
     * @return 인스턴스 row 또는 {@code null} (조회 실패 시 default 동작 유도)
     */
    private LineInstance loadInstanceSafely(String instanceId) {
        try {
            return activityRepository.findInstanceById(instanceId);
        } catch (EmptyResultDataAccessException ex) {
            log.warn("Instance not found — id={}, fallback to defaults", instanceId);
            return null;
        } catch (Exception ex) {
            log.warn("Instance 조회 실패 — id={}, fallback to defaults ({}: {})",
                    instanceId, ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    /**
     * 활동 실행 직전 필요한 컨텍스트 + 옵션 묶음.
     *
     * @param context {@link DefaultLineContext} (executionId / runtimeParams 모두 주입됨)
     * @param options {@link RunOptions} (onFailure / webhook override 등 활용)
     */
    public record Bundle(DefaultLineContext context, RunOptions options) {
    }
}
