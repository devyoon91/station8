package com.station8.app.definition;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * #168 — Project CRUD REST API.
 *
 * <ul>
 *   <li>POST   /api/line/projects        — 생성</li>
 *   <li>GET    /api/line/projects        — 목록</li>
 *   <li>GET    /api/line/projects/{id}   — 단건 조회</li>
 *   <li>PUT    /api/line/projects/{id}   — 이름/설명 수정</li>
 *   <li>DELETE /api/line/projects/{id}   — 소프트 삭제 (default 보호)</li>
 * </ul>
 *
 * <p>인증된 USER만 접근 가능. Phase 3에서 project 단위 ACL grant 추가 예정.</p>
 */
@RestController
@RequestMapping("/api/line/projects")
public class LineProjectController {

    private final LineProjectService service;

    public LineProjectController(LineProjectService service) {
        this.service = service;
    }

    /**
     * 신규 프로젝트 생성. 인증된 USER만 가능.
     *
     * @param req 생성 요청 — {@code @Valid}로 1차 검증 (projectNm 필수/길이)
     * @return 201 + {@code {projectId}}
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> create(@Valid @RequestBody LineProjectRequest req) {
        String id = service.createProject(req, currentUsername());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("projectId", id));
    }

    /**
     * 살아있는 프로젝트 목록.
     */
    @GetMapping
    public List<LineProjectResponse> list() {
        return service.listProjects();
    }

    /**
     * 단건 조회.
     */
    @GetMapping("/{id}")
    public LineProjectResponse get(@PathVariable("id") String id) {
        return service.getProject(id);
    }

    /**
     * 이름/설명 수정. 인증된 USER만 가능.
     *
     * @return 204 No Content
     */
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> update(@PathVariable("id") String id,
                                       @Valid @RequestBody LineProjectRequest req) {
        service.updateProject(id, req, currentUsername());
        return ResponseEntity.noContent().build();
    }

    /**
     * 소프트 삭제. Default project는 삭제 불가.
     *
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    /** 현재 인증된 사용자 username — anonymous인 경우 null. */
    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }
}
