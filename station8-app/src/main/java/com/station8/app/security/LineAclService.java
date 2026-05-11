package com.station8.app.security;

import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineSchedule;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.repository.LineScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * #140 — 라인 정의별 ACL 평가 SPI. SpEL bean으로 노출 — {@code @PreAuthorize("@lineAcl.canExecute(#id)")}.
 *
 * <h3>평가 규칙</h3>
 * <ol>
 *   <li>인증 안 된 요청 → false</li>
 *   <li>전역 {@code ROLE_ADMIN} 권한 보유 → 자동 통과 (모든 정의의 모든 권한)</li>
 *   <li>정의에 ACL entry 0건 → legacy/open 상태로 간주, 인증된 USER 모두 통과</li>
 *   <li>ACL entry 있음 → 사용자의 명시 grant만 인정. {@code ADMIN}은 {WRITE/EXECUTE/SCHEDULE/READ} 자동 cascade</li>
 * </ol>
 *
 * <h3>READ enforcement (#159)</h3>
 * <p>{@link #visibleDefinitionIds(java.util.Collection)} / {@link #visibleWorkflowNames(java.util.Collection)}로
 * 라인 정의 list / Dashboard / DLQ / Schedules 페이지에서 사용자별 가시성 필터를 제공.
 * legacy 정의(ACL 0건)는 모든 인증된 USER에게 노출되므로 후방 호환 유지.</p>
 *
 * <h3>인스턴스 → 정의 해석</h3>
 * <p>인스턴스는 정의 ID가 아닌 {@code workflowName}만 보유. {@link #canExecuteInstance(String)}는
 * 인스턴스 → workflow_name → 가장 최근 active 정의로 해석 후 EXECUTE 검사.</p>
 */
@Service("lineAcl")
public class LineAclService {

    private static final Logger log = LoggerFactory.getLogger(LineAclService.class);

    private final LineAclRepository aclRepo;
    private final LineUserRepository userRepo;
    private final LineDefinitionRepository definitionRepo;
    private final ActivityRepository activityRepo;
    private final LineScheduleRepository scheduleRepo;

    public LineAclService(LineAclRepository aclRepo,
                          LineUserRepository userRepo,
                          LineDefinitionRepository definitionRepo,
                          ActivityRepository activityRepo,
                          LineScheduleRepository scheduleRepo) {
        this.aclRepo = aclRepo;
        this.userRepo = userRepo;
        this.definitionRepo = definitionRepo;
        this.activityRepo = activityRepo;
        this.scheduleRepo = scheduleRepo;
    }

    public boolean canRead(String definitionId) { return hasPermission(definitionId, "READ"); }
    public boolean canWrite(String definitionId) { return hasPermission(definitionId, "WRITE"); }
    public boolean canExecute(String definitionId) { return hasPermission(definitionId, "EXECUTE"); }
    public boolean canSchedule(String definitionId) { return hasPermission(definitionId, "SCHEDULE"); }
    public boolean canAdmin(String definitionId) { return hasPermission(definitionId, "ADMIN"); }

    /**
     * 스케줄 ID → 정의 ID 해석 후 SCHEDULE 검사. 스케줄 없으면 false.
     */
    public boolean canScheduleByScheduleId(String scheduleId) {
        if (!isAuthenticated()) return false;
        if (isGlobalAdmin()) return true;
        try {
            LineSchedule sch = scheduleRepo.findById(scheduleId);
            if (sch == null) return false;
            return canSchedule(sch.definitionId());
        } catch (Exception ex) {
            log.debug("canScheduleByScheduleId — schedule not found: {}", scheduleId);
            return false;
        }
    }

    /**
     * 인스턴스 ID → 정의 ID 해석 후 EXECUTE 검사.
     * 정의를 찾지 못하면 (deactivate/삭제) 글로벌 ADMIN만 허용.
     */
    public boolean canExecuteInstance(String instanceId) {
        if (!isAuthenticated()) return false;
        if (isGlobalAdmin()) return true;

        LineInstance inst;
        try {
            inst = activityRepo.findInstanceById(instanceId);
        } catch (Exception ex) {
            log.debug("canExecuteInstance — instance not found: {}", instanceId);
            return false;
        }
        if (inst == null) return false;

        LineDefinition def = definitionRepo.findActiveDefinitionByName(inst.workflowName());
        if (def == null) {
            log.debug("canExecuteInstance — active definition for workflowName='{}' not found", inst.workflowName());
            return false;  // 글로벌 ADMIN은 위에서 이미 통과
        }
        return canExecute(def.id());
    }

    // ========== #159 — list 필터링 (Dashboard / DLQ / Schedules / Definitions) ==========

    /**
     * 현재 사용자가 볼 수 있는 라인 정의 ID 집합.
     *
     * <ul>
     *   <li>{@code null} 반환 — 필터 불필요 (글로벌 ADMIN). 호출 측은 모든 행을 노출</li>
     *   <li>비어 있는 set — 인증 없거나 사용자 매핑 실패 — 아무것도 노출하지 않음</li>
     *   <li>그 외 set — 명시 grant 가진 정의 + ACL entry 0건인 legacy 정의를 합집합</li>
     * </ul>
     *
     * <p>SQL 1회(ACL entry 보유 정의) + SQL 1회(사용자 grant) + 인메모리 합집합.
     * Active 정의 enumeration은 호출 측에서 이미 갖고 있으므로 본 메서드는 def 목록을 다시 로드하지 않음 —
     * 필터 적용은 {@link #filterVisibleDefinitionIds(java.util.Collection)}로 위임.</p>
     */
    public Set<String> visibleDefinitionIds(java.util.Collection<String> candidateDefinitionIds) {
        if (isGlobalAdmin()) return null;
        if (!isAuthenticated()) return Set.of();
        String userId = currentUserId();
        if (userId == null) return Set.of();

        Set<String> withAcl = aclRepo.findDefinitionIdsWithAcl();
        Set<String> grants = aclRepo.findDefinitionIdsForUser(userId);

        Set<String> visible = new HashSet<>();
        for (String id : candidateDefinitionIds) {
            // legacy(ACL 0건) 또는 사용자에게 명시 grant 있는 정의
            if (!withAcl.contains(id) || grants.contains(id)) visible.add(id);
        }
        return visible;
    }

    /**
     * {@link #visibleDefinitionIds(java.util.Collection)}와 동일한 의미를 workflow_name 기준으로 반환.
     * 인스턴스/DLQ는 정의 ID가 아닌 workflow_name(정의 이름)을 보유하므로 dashboard/dlq 필터에 사용.
     *
     * @param activeDefinitions 현재 활성 정의 목록 (호출자가 이미 로드)
     * @return null = 필터 불필요(ADMIN), 그 외 = 노출 가능한 workflow_name 집합
     */
    public Set<String> visibleWorkflowNames(java.util.Collection<? extends LineDefinition> activeDefinitions) {
        if (isGlobalAdmin()) return null;
        if (!isAuthenticated()) return Set.of();
        String userId = currentUserId();
        if (userId == null) return Set.of();

        Set<String> withAcl = aclRepo.findDefinitionIdsWithAcl();
        Set<String> grants = aclRepo.findDefinitionIdsForUser(userId);

        Set<String> visibleNames = new HashSet<>();
        for (LineDefinition d : activeDefinitions) {
            if (!withAcl.contains(d.id()) || grants.contains(d.id())) {
                if (d.definitionNm() != null) visibleNames.add(d.definitionNm());
            }
        }
        return visibleNames;
    }

    private boolean hasPermission(String definitionId, String required) {
        if (!isAuthenticated()) return false;
        if (isGlobalAdmin()) return true;

        // 정의에 ACL entry 0건 → legacy/open (모든 인증된 USER 통과)
        if (aclRepo.countEntriesForDefinition(definitionId) == 0) return true;

        String userId = currentUserId();
        if (userId == null) return false;

        List<String> grants = aclRepo.findPermissionsForUser(definitionId, userId);
        if (grants.isEmpty()) return false;
        if (grants.contains("ADMIN")) return true;  // ADMIN cascade
        if ("READ".equals(required)) return true;   // any explicit grant implies READ
        return grants.contains(required);
    }

    /** 현재 인증 컨텍스트 — 단위 테스트에서 stub override 가능하도록 protected. */
    protected boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }

    protected boolean isGlobalAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    protected String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private String currentUserId() {
        String username = currentUsername();
        if (username == null) return null;
        LineUser user = userRepo.findByUsername(username);
        return user == null ? null : user.id();
    }
}
