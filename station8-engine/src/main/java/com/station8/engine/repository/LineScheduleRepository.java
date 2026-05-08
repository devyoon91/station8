package com.station8.engine.repository;

import com.station8.engine.entity.LineSchedule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정기 실행 스케줄(U_WF_SCHEDULE) 리포지토리.
 * M2-2 폴러가 ``findDueWithLock``로 만료된 cron을 가져가고, ``markRun``으로 다음 실행 시각을 갱신한다.
 */
public interface LineScheduleRepository {

    /** 신규 스케줄 등록. */
    void insert(LineSchedule schedule);

    /** ID로 조회. 없으면 null. */
    LineSchedule findById(String id);

    /** 전체 활성 스케줄 (삭제되지 않은). */
    List<LineSchedule> findAll();

    /**
     * SKIP LOCKED로 만료된 스케줄을 잠금하여 가져온다 (분산 환경에서 중복 트리거 방지).
     * 조건: PAUSED_FL='N' AND DEL_FL='N' AND NEXT_RUN_DT IS NOT NULL AND NEXT_RUN_DT <= now
     */
    List<LineSchedule> findDueWithLock(int limit);

    /** 다음 실행 시각 + LAST_RUN_DT 갱신 (폴러가 트리거 후 호출). */
    void markRun(String scheduleId, LocalDateTime nextRunDt, LocalDateTime lastRunDt);

    /** cron 표현식 또는 nextRunDt 변경. */
    void updateCron(String scheduleId, String cronExpr, LocalDateTime nextRunDt);

    /** 일시중지/재개. */
    void setPaused(String scheduleId, boolean paused);

    /** 소프트 삭제. */
    void softDelete(String scheduleId);
}
