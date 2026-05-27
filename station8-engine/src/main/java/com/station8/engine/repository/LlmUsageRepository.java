package com.station8.engine.repository;

import com.station8.engine.entity.LlmUsageEntry;

import java.util.List;

/**
 * H_LINE_LLM_USAGE 접근 (#339). append-only — insert + 조회만.
 */
public interface LlmUsageRepository {

    /**
     * 사용량 기록 삽입. {@code id}가 null이면 구현체가 UUID 생성.
     *
     * @param entry 기록 (id는 null 허용)
     * @return 실제 사용된 id
     */
    String insert(LlmUsageEntry entry);

    /** 인스턴스 단위 사용량 조회 (생성 시각 오름차순). */
    List<LlmUsageEntry> findByInstanceId(String instanceId);
}
