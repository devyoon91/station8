package com.station8.engine.repository;

import com.station8.engine.entity.DlqEntry;

import java.util.List;

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

    /**
     * 특정 DLQ 항목을 ID로 조회합니다.
     */
    DlqEntry findById(String id);

    /**
     * DLQ 항목의 상태를 업데이트합니다 (REQUEUED, DISCARDED 등).
     */
    void updateStatus(String id, String newStatus);
}
