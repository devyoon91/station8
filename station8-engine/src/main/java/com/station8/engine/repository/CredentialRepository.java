package com.station8.engine.repository;

import com.station8.engine.entity.Credential;

import java.util.List;

/**
 * U_LINE_CREDENTIAL — M17 (#270) credential vault repository.
 *
 * <p>모든 메서드는 {@code DEL_FL = 'N'}만 다룬다. soft delete 후 재조회 안 됨.
 * {@code valueEnc}는 ciphertext 그대로 read/write — 암호화/복호화는 호출부 책임.</p>
 */
public interface CredentialRepository {

    /**
     * 신규 등록. ID는 호출부에서 UUID 등으로 생성해 전달.
     *
     * @throws org.springframework.dao.DuplicateKeyException 같은 NAME 중복 시
     */
    void insert(Credential credential);

    /** ID 단건 조회. 없으면 null. */
    Credential findById(String id);

    /** NAME 단건 조회 — 표현식 평가 시 {@code $credentials.<NAME>} 룩업에 사용. 없으면 null. */
    Credential findByName(String name);

    /** 활성 credential 전체 (USE_FL='Y' AND DEL_FL='N'), NAME 정렬. */
    List<Credential> findAllActive();

    /** name/type/schemaJson/valueEnc 갱신. value 회전 시에도 본 메서드 호출. */
    void update(Credential credential);

    /** soft delete — DEL_FL='Y'. */
    void softDelete(String id, String editId);
}
