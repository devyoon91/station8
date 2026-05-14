package com.station8.app.definition;

import com.station8.engine.core.RunOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * #179 — DAG 정의 등록/수정/삭제 + 즉시 실행 진입점 Facade.
 *
 * <p>외부에 노출하는 메서드 시그니처는 그대로 유지되며, 실제 책임은 4개 sub-service로 분해되었다:</p>
 *
 * <ul>
 *   <li>{@link LineDefinitionPersistence} — 정의 row + 그래프(노드/엣지) CRUD + getDefinition</li>
 *   <li>{@link LineDefinitionMetadata}    — 태그 정규화 + 영속성 (#142)</li>
 *   <li>{@link LineDefinitionAclBootstrap} — creator에게 ADMIN auto-grant (#140)</li>
 *   <li>{@link LineRunner}                — 즉시 실행 + concurrency 게이트 (#141/#165/#177)</li>
 * </ul>
 *
 * <p>본 Facade는 호출 순서만 조율한다 — 비즈니스 로직은 모두 sub-service에 위임. 트랜잭션 경계는
 * 본 Facade의 {@code @Transactional}에서 형성되며, sub-service들은 같은 트랜잭션 안에서 동작한다
 * (Spring 기본 propagation REQUIRED).</p>
 */
@Service
public class LineDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(LineDefinitionService.class);

    private final LineDefinitionPersistence persistence;
    private final LineDefinitionMetadata metadata;
    private final LineDefinitionAclBootstrap aclBootstrap;
    private final LineRunner runner;

    /**
     * 컴포넌트 의존성 주입 — 모든 비즈니스 로직은 sub-service에 위임.
     *
     * @param persistence  정의 + 그래프 CRUD
     * @param metadata     태그 정규화/저장
     * @param aclBootstrap 생성 시 ADMIN auto-grant
     * @param runner       즉시 실행 + 동시성 게이트
     */
    public LineDefinitionService(LineDefinitionPersistence persistence,
                                 LineDefinitionMetadata metadata,
                                 LineDefinitionAclBootstrap aclBootstrap,
                                 LineRunner runner) {
        this.persistence = persistence;
        this.metadata = metadata;
        this.aclBootstrap = aclBootstrap;
        this.runner = runner;
    }

    /**
     * 새 DAG 정의를 등록한다. 같은 {@code definitionNm}이 이미 있으면 새 버전으로 추가.
     *
     * <p>흐름 — Persistence가 정의 row + 그래프를 저장 → Metadata가 태그 정규화/저장 →
     * AclBootstrap이 creator에게 ADMIN 자동 부여.</p>
     *
     * @param req 정의 등록 요청
     * @return 생성된 definitionId
     */
    @Transactional
    public String createDefinition(DagDefinitionRequest req) {
        String definitionId = persistence.createDefinition(req);
        metadata.persistTags(definitionId, req.tags());
        log.info("DAG 정의 등록: id={}, nm={}, tags={}",
                definitionId, req.definitionNm(), LineDefinitionMetadata.normalize(req.tags()));
        aclBootstrap.autoGrantAdminToCreator(definitionId);
        return definitionId;
    }

    /**
     * 정의 단건 조회 — 메타 + 노드 + 엣지 + 태그를 합쳐 반환.
     *
     * @param definitionId 조회 대상 ID
     * @return 정의 응답 DTO
     */
    @Transactional(readOnly = true)
    public DagDefinitionResponse getDefinition(String definitionId) {
        return persistence.getDefinition(definitionId);
    }

    /**
     * 정의의 메타 + 그래프를 통째로 교체한다 (버전 유지). 새 버전으로 저장하려면
     * {@link #createDefinition}을 다시 호출하라.
     *
     * <p>흐름 — Persistence가 정의 row + 그래프 교체 → Metadata가 태그 통째로 교체 (delete + insert).</p>
     *
     * @param definitionId 교체 대상 정의 ID
     * @param req          새 정의 페이로드
     */
    @Transactional
    public void replaceDefinition(String definitionId, DagDefinitionRequest req) {
        persistence.replaceDefinition(definitionId, req);
        metadata.replaceTags(definitionId, req.tags());
        log.info("DAG 정의 교체: id={}, tags={}",
                definitionId, LineDefinitionMetadata.normalize(req.tags()));
    }

    /**
     * 정의 + 노드 + 엣지를 모두 소프트 삭제. 이미 삭제된 경우 멱등.
     *
     * @param definitionId 삭제 대상 정의 ID
     */
    @Transactional
    public void deleteDefinition(String definitionId) {
        persistence.softDelete(definitionId);
    }

    /**
     * 즉시 실행 (후방 호환) — 옵션 없이 default 사용.
     *
     * @param definitionId 실행 대상 정의 ID
     * @param inputData    인스턴스 입력 데이터
     * @return 생성된 instanceId
     */
    @Transactional
    public String runDefinition(String definitionId, String inputData) {
        return runDefinition(definitionId, inputData, RunOptions.defaults());
    }

    /**
     * 즉시 실행 + 인스턴스 옵션 (#134). SKIP 시 {@link IllegalStateException}.
     *
     * <p>SKIP을 정상 흐름으로 처리하려면 {@link #runDefinitionWithResult}를 사용.</p>
     *
     * @param definitionId 실행 대상 정의 ID
     * @param inputData    인스턴스 입력 데이터
     * @param options      인스턴스 옵션 ({@code null}이면 default)
     * @return 생성된 instanceId
     * @throws IllegalStateException SKIP_IF_RUNNING 정책으로 차단된 경우
     */
    @Transactional
    public String runDefinition(String definitionId, String inputData, RunOptions options) {
        RunResult result = runner.runWithResult(definitionId, inputData, options);
        if (result.skipped()) {
            // 후방 호환 — 기존 callers는 String 기대. SKIP은 IllegalStateException으로.
            throw new IllegalStateException("동시 실행 SKIP: " + result.reason()
                    + " (conflicting instance: " + result.conflictingInstanceId() + ")");
        }
        return result.instanceId();
    }

    /**
     * #141 — 즉시 실행. SKIP_IF_RUNNING 정책 시 skip 결과를 반환 (예외 X).
     *
     * @param definitionId 실행 대상 정의 ID
     * @param inputData    인스턴스 입력 데이터
     * @param options      인스턴스 옵션 ({@code null}이면 default)
     * @return skip된 경우 {@link RunResult#skipped}, 그렇지 않으면 {@link RunResult#started}
     */
    @Transactional
    public RunResult runDefinitionWithResult(String definitionId, String inputData, RunOptions options) {
        return runner.runWithResult(definitionId, inputData, options);
    }
}
