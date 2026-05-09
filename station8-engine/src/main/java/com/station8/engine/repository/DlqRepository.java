package com.station8.engine.repository;

import com.station8.engine.entity.DlqEntry;

import java.util.List;
import java.util.Map;

/**
 * Dead Letter Queue(H_LINE_DLQ) 테이블에 대한 리포지토리 인터페이스.
 * DLQ 적재, 조회, 재처리(Requeue), 폐기(Discard) 기능을 제공합니다.
 */
public interface DlqRepository {

    /**
     * 최종 실패한 액티비티를 DLQ에 적재합니다.
     */
    void insert(DlqEntry entry);

    /**
     * 전체 DLQ 목록을 조회합니다.
     */
    List<DlqEntry> findAll();

    /** DLQ 페이지 조회 — 정렬은 ``REG_DT DESC``(최근 실패 우선) (#97). */
    List<DlqEntry> findPage(int offset, int limit);

    /** DLQ 총 행 수 (#97). */
    long count();

    /**
     * DLQ_STATUS_ST별 카운트 — DLQ 헤더 통계 카드용 (NEW/REQUEUED/DISCARDED) (#97).
     */
    Map<String, Long> countByStatus();

    /**
     * 특정 DLQ 항목을 ID로 조회합니다.
     */
    DlqEntry findById(String id);

    /**
     * DLQ 항목의 상태를 업데이트합니다 (REQUEUED, DISCARDED 등).
     */
    void updateStatus(String id, String newStatus);
}
