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
     * 다음 단계 또는 재시도 작업을 PENDING 상태로 생성합니다 (레거시 선형 모드).
     *
     * @param instanceId 라인 인스턴스 ID
     * @param activityName 액티비티 이름
     * @param inputData 입력 JSON 문자열
     * @param nextRetryDt 다음 실행(재시도) 예정 시각 (없으면 즉시 실행 대상으로 간주)
     * @return 생성된 실행 ID
     */
    String createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt);

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
     * 단일 액티비티 실행을 ID로 조회합니다.
     */
    ActivityExecution findById(String executionId);

    /**
     * 특정 인스턴스에서 특정 역의 실행을 조회합니다.
     */
    ActivityExecution findByInstanceAndNode(String instanceId, String nodeId);

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
     */
    List<com.station8.engine.entity.LineInstance> findInstancesPage(
            String workflowName, String statusSt, String instanceId, int offset, int limit);

    /**
     * 동일 필터 조건에서의 총 행 수 (#97 — 페이지 네비용).
     */
    long countInstances(String workflowName, String statusSt, String instanceId);

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
}

