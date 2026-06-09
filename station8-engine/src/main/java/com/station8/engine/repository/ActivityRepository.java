package com.station8.engine.repository;

import com.station8.engine.entity.ActivityExecution;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DB 기반 작업 큐 폴링을 위한 리포지토리 인터페이스
 */
public interface ActivityRepository {
    
    /**
     * Oracle/MariaDB의 SKIP LOCKED를 사용하여 처리 가능한 작업을 조회하고 잠금합니다.
     *
     * @param limit 조회할 최대 작업 수
     * @return 잠금된 Activity 실행 목록
     */
    List<ActivityExecution> findPendingActivitiesWithLock(int limit);
    
    /**
     * 실행 상태 및 결과를 업데이트합니다.
     *
     * @param activityExecution 업데이트할 실행 정보
     */
    void updateStatus(ActivityExecution activityExecution);

    /**
     * 다음 단계 또는 재시도 작업을 PENDING 상태로 생성합니다.
     *
     * <p>#278 — {@code nodeId} 보존: DAG 모드 활동의 retry가 새 row를 만들 때
     * 원본 활동의 {@code nodeId}를 그대로 전달해야 한다. 이전엔 항상 {@code NULL}로
     * 박혀 retry row가 DAG fan-out에서 매칭 안 되고 누적되는 데이터 손상이 발생했음.
     * legacy/linear 모드는 {@code nodeId = null} 그대로.</p>
     *
     * @param instanceId 라인 인스턴스 ID
     * @param nodeId DAG 노드 ID (legacy/linear 모드면 {@code null})
     * @param activityName 액티비티 이름
     * @param inputData 입력 JSON 문자열
     * @param nextRetryDt 다음 실행(재시도) 예정 시각 (없으면 즉시 실행 대상으로 간주)
     * @return 생성된 실행 ID
     */
    String createPending(String instanceId, String nodeId, String activityName, String inputData, LocalDateTime nextRetryDt);

    /**
     * 후방 호환 — {@code nodeId} 없는 호출은 legacy/linear 모드로 간주, NULL nodeId.
     * 새 코드는 위 5-arg 버전 사용 권장 (#278).
     */
    default String createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt) {
        return createPending(instanceId, null, activityName, inputData, nextRetryDt);
    }

    /**
     * DAG 모드에서 역 단위 액티비티 실행을 생성합니다.
     * STATUS_ST는 호출자가 결정 (시작 역은 PENDING, 후행 역은 WAITING_DEPENDENCIES).
     *
     * @param instanceId 라인 인스턴스 ID
     * @param nodeId U_LINE_STATION.ID
     * @param activityName 액티비티 이름
     * @param statusSt 초기 상태 (PENDING 또는 WAITING_DEPENDENCIES)
     * @param inputData 입력 JSON
     * @return 생성된 실행 ID
     */
    String createForNode(String instanceId, String nodeId, String activityName, String statusSt, String inputData);

    /**
     * M22 fan-out — 특정 item 레인 인덱스로 역 실행 행을 생성한다. materialize 시 item당 1행.
     *
     * @param itemIndex fan-out 레인 인덱스 (0..K-1)
     * @return 생성된 실행 ID
     */
    String createForNodeItem(String instanceId, String nodeId, String activityName, String statusSt,
                             String inputData, int itemIndex);

    /**
     * M22 — retry 행이 fan-out 레인을 보존하도록 itemIndex를 명시하는 createPending.
     * 기존 5-arg는 itemIndex=0으로 위임된다.
     */
    String createPending(String instanceId, String nodeId, String activityName, String inputData,
                         LocalDateTime nextRetryDt, int itemIndex);

    /**
     * 단일 액티비티 실행을 ID로 조회합니다.
     */
    ActivityExecution findById(String executionId);

    /**
     * 특정 인스턴스에서 특정 역의 실행을 조회합니다.
     *
     * <p>fan-out으로 한 역에 여러 item 행이 있으면 임의의 1행을 반환한다 — 비-fan-out(기존)
     * 경로의 시맨틱을 보존하기 위함. 전체 item 행이 필요하면 {@link #findAllByInstanceAndNode}.</p>
     */
    ActivityExecution findByInstanceAndNode(String instanceId, String nodeId);

    /**
     * M22 — 한 (instance, node)에 속한 모든 item 레인 실행 행을 반환한다. fan-in 판정/수집용.
     * 비-fan-out 노드는 보통 1행.
     */
    List<ActivityExecution> findAllByInstanceAndNode(String instanceId, String nodeId);

    /**
     * 액티비티 실행을 WAITING_DEPENDENCIES → PENDING 으로 전이시킵니다 (인터프리터용).
     */
    void promoteToPending(String executionId);

    /**
     * 라인 인스턴스 목록을 조회합니다.
     */
    List<com.station8.engine.entity.LineInstance> findAllInstances();

    /**
     * 인스턴스 페이지 조회 — 필터(부분일치 / 정확일치)와 페이징을 SQL로 처리한다 (#97).
     * 모든 인자는 ``null``/빈문자 허용 → 해당 조건 무시.
     *
     * @param workflowName ``WORKFLOW_NAME LIKE '%name%'``
     * @param statusSt     ``STATUS_ST = ?`` 정확일치
     * @param instanceId   ``ID LIKE '%id%'`` 부분일치
     * @param offset       건너뛸 행 수 (0-based)
     * @param limit        반환할 최대 행 수
     * @deprecated #137 — {@link #findInstancesPage(InstanceQueryFilter, int, int)} 사용 권장.
     *             후방 호환을 위해 default 메서드로 남겨둠 (단일 status를 list로 변환해 위임).
     */
    @Deprecated
    default List<com.station8.engine.entity.LineInstance> findInstancesPage(
            String workflowName, String statusSt, String instanceId, int offset, int limit) {
        return findInstancesPage(InstanceQueryFilter.ofLegacy(workflowName, statusSt, instanceId),
                offset, limit);
    }

    /**
     * 인스턴스 페이지 조회 (#137) — {@link InstanceQueryFilter}로 모든 필터 + 정렬 지정.
     */
    List<com.station8.engine.entity.LineInstance> findInstancesPage(
            InstanceQueryFilter filter, int offset, int limit);

    /**
     * 동일 필터 조건에서의 총 행 수 (#97 — 페이지 네비용).
     *
     * @deprecated #137 — {@link #countInstances(InstanceQueryFilter)} 사용 권장.
     */
    @Deprecated
    default long countInstances(String workflowName, String statusSt, String instanceId) {
        return countInstances(InstanceQueryFilter.ofLegacy(workflowName, statusSt, instanceId));
    }

    /** 인스턴스 페이지 카운트 (#137). */
    long countInstances(InstanceQueryFilter filter);

    /**
     * 상태별 인스턴스 카운트 — Dashboard 헤더 통계 카드용 (#97).
     * 키는 ``STATUS_ST`` 그대로(예: RUNNING/COMPLETED/FAILED). 값은 행 수.
     */
    Map<String, Long> countInstancesByStatus();

    /**
     * 특정 인스턴스의 상세 정보를 조회합니다.
     */
    com.station8.engine.entity.LineInstance findInstanceById(String instanceId);

    /**
     * 특정 인스턴스에 속한 액티비티 실행 이력을 조회합니다.
     */
    List<ActivityExecution> findActivitiesByInstanceId(String instanceId);

    /**
     * 실패하거나 중단된 작업을 다시 PENDING 상태로 복구합니다.
     */
    void resetToPending(String executionId);

    // ========== #164 — Pipeline 모드 게이트 지원 ==========

    /**
     * #164 — 인스턴스의 특정 노드 실행이 COMPLETED 됐는지.
     * Pipeline 1 게이트 (선행 인스턴스의 같은 노드 완료 검사)에 사용.
     */
    boolean isNodeCompleted(String instanceId, String nodeId);

    /**
     * #164 — 인스턴스가 노드 집합 중 어느 하나라도 STARTED(=RUNNING/COMPLETED/FAILED) 했는지.
     * Pipeline 2/3 게이트에 사용 — gap 단계의 노드가 시작됐는지 검사.
     */
    boolean isAnyNodeStarted(String instanceId, java.util.Collection<String> nodeIds);

    /**
     * #164 — 게이트에 막힌 활동을 다시 PENDING으로 되돌리고, 다음 폴링 시각을 지연.
     * findPendingActivitiesWithLock가 RUNNING으로 마킹한 직후 게이트 차단을 발견했을 때 사용.
     */
    void revertGateBlocked(String executionId, LocalDateTime nextRetryDt);

    /**
     * 인스턴스 종료(#101) 시 — {@code PENDING} / {@code WAITING_DEPENDENCIES} 액티비티들을 한 번에
     * 새 상태(예: {@code TERMINATED})로 전이한다. {@code RUNNING}/{@code COMPLETED}/{@code FAILED}는
     * 영향 없음.
     *
     * @return 영향받은 행 수
     */
    int bulkUpdateNotStartedStatuses(String instanceId, String toStatus);
}

