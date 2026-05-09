package com.station8.engine.repository;

import com.station8.engine.entity.DataSourceDefinition;

import java.util.List;

/**
 * 어드민 UI로 등록된 동적 DataSource 정의 (#110)의 영속화 리포지토리.
 *
 * <p>{@code DataSourceRegistry}와는 다른 역할 — 본 리포지토리는 "DB에 저장된 raw 정의"를
 * 다루고, registry는 "런타임에 활성화된 풀"을 다룬다. 부팅 시 본 리포지토리에서 enabled 항목을
 * 모두 읽어 registry에 등록하고, UI 액션도 양쪽을 같이 갱신한다.</p>
 */
public interface DataSourceDefinitionRepository {

    void insert(DataSourceDefinition def);

    /** ID로 조회. 없으면 null. */
    DataSourceDefinition findById(String id);

    /** 이름으로 조회. 없으면 null. */
    DataSourceDefinition findByName(String name);

    /** DEL_FL='N' 전체 — UI 목록 + 부팅 로딩에서 사용. */
    List<DataSourceDefinition> findAll();

    /**
     * 연결 정보 + 옵션 갱신. 비밀번호는 비어있지 않을 때만 변경.
     *
     * @param keepPasswordIfBlank true면 password가 빈 문자열일 때 기존 값 유지
     *                            (어드민 UI 폼에서 빈 password = "변경 안 함" 의미)
     */
    void update(DataSourceDefinition def, boolean keepPasswordIfBlank);

    /** ENABLED_FL 토글 (운영자가 일시 비활성화). */
    void setEnabled(String id, boolean enabled);

    /** 소프트 삭제 (DEL_FL='Y'). 풀 close는 호출자(서비스)가 따로 한다. */
    void softDelete(String id);
}
