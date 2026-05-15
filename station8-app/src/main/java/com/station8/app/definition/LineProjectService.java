package com.station8.app.definition;

import com.station8.engine.entity.LineProject;
import com.station8.engine.repository.LineProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * #168 — 라인 정의 컨테이너인 Project의 CRUD 진입점.
 *
 * <h3>Phase 1 범위</h3>
 * <ul>
 *   <li>Project CRUD (create / list / get / update / delete)</li>
 *   <li>Default project는 {@link LineProjectSeeder}가 부팅 시 시드</li>
 *   <li>권한(#140 ACL 확장)은 Phase 3 follow-up — 현재는 USER 권한 가진 사용자라면 모두 접근 가능</li>
 * </ul>
 */
@Service
public class LineProjectService {

    private static final Logger log = LoggerFactory.getLogger(LineProjectService.class);

    private final LineProjectRepository repository;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param repository Project 영속성 repository
     */
    public LineProjectService(LineProjectRepository repository) {
        this.repository = repository;
    }

    /**
     * 신규 프로젝트 등록. 이름 중복 시 {@link IllegalArgumentException}.
     *
     * @param req 생성 요청 (이름/설명)
     * @param regId 생성자 식별자 (인증 컨텍스트가 있는 경우 username)
     * @return 새로 생성된 projectId
     */
    @Transactional
    public String createProject(LineProjectRequest req, String regId) {
        if (repository.existsByName(req.projectNm())) {
            throw new IllegalArgumentException("이미 존재하는 프로젝트 이름입니다: " + req.projectNm());
        }
        String id = UUID.randomUUID().toString();
        repository.insert(new LineProject(
                id, req.projectNm(), req.description(),
                "N",
                null, regId != null ? regId : "api", null, null
        ));
        log.info("프로젝트 등록: id={}, nm={}, by={}", id, req.projectNm(), regId);
        return id;
    }

    /**
     * 살아있는 모든 프로젝트 목록 (정렬: 이름 ASC).
     */
    @Transactional(readOnly = true)
    public List<LineProjectResponse> listProjects() {
        return repository.findAll().stream().map(LineProjectResponse::from).toList();
    }

    /**
     * 단건 조회. 존재하지 않으면 {@link IllegalArgumentException}.
     */
    @Transactional(readOnly = true)
    public LineProjectResponse getProject(String projectId) {
        LineProject p = repository.findById(projectId);
        if (p == null) {
            throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
        }
        return LineProjectResponse.from(p);
    }

    /**
     * 이름/설명 업데이트. 이름 변경 시 중복 검사.
     */
    @Transactional
    public void updateProject(String projectId, LineProjectRequest req, String editId) {
        LineProject existing = repository.findById(projectId);
        if (existing == null) {
            throw new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId);
        }
        // 이름 변경 시 — 본인 외 동일 이름 존재 검사
        if (!existing.projectNm().equals(req.projectNm()) && repository.existsByName(req.projectNm())) {
            throw new IllegalArgumentException("이미 존재하는 프로젝트 이름입니다: " + req.projectNm());
        }
        repository.updateMeta(projectId, req.projectNm(), req.description(),
                editId != null ? editId : "api");
        log.info("프로젝트 업데이트: id={}, newNm={}, by={}", projectId, req.projectNm(), editId);
    }

    /**
     * 소프트 삭제. Default project는 삭제 불가 (시스템 fallback이 깨지지 않도록).
     *
     * <p>본 PR에서는 프로젝트 안의 정의들이 어떻게 되는지에 대한 cascading 정책은 정하지 않음 —
     * 향후 follow-up에서 "삭제 전 정의를 다른 프로젝트로 이동" UX 도입 예정. 현재는 정의는 그대로
     * 두고 프로젝트만 soft-delete (정의의 PROJECT_ID 참조는 dangling 가능).</p>
     */
    @Transactional
    public void deleteProject(String projectId) {
        if (LineProject.DEFAULT_PROJECT_ID.equals(projectId)) {
            throw new IllegalArgumentException("default 프로젝트는 삭제할 수 없습니다.");
        }
        repository.softDelete(projectId);
        log.info("프로젝트 소프트 삭제: id={}", projectId);
    }
}
