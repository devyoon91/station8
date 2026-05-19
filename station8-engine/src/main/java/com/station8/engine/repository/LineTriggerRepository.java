package com.station8.engine.repository;

import com.station8.engine.entity.LineTrigger;

import java.util.List;

/**
 * M20 (#310) — U_LINE_TRIGGER repository.
 *
 * <p>본 sub-issue는 webhook lookup만 필요 — 전체 CRUD는 #311에서 추가.</p>
 */
public interface LineTriggerRepository {

    /** webhook key 단건 조회. 비활성/삭제 포함 — 호출자가 {@link LineTrigger#isActive()}로 판단. */
    LineTrigger findByKey(String triggerKey);

    /** ID 단건 조회. 없으면 null. */
    LineTrigger findById(String id);

    /** active + 미삭제 trigger 전체. CRUD UI(#311)에서 사용. */
    List<LineTrigger> findAllActive();

    /** 신규 등록. ID/createdAt 등은 호출부에서 채워 전달. */
    void insert(LineTrigger trigger);

    /** 기본 필드 갱신. */
    void update(LineTrigger trigger);

    /** soft delete. */
    void softDelete(String id, String editId);
}
