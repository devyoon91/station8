package com.station8.app.controller.dashboard;

import com.station8.app.security.LineAclService;
import com.station8.app.util.PaginationModel;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.InstanceQueryFilter;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dashboard 페이지의 model 조립을 담당하는 빌더.
 *
 * <p>이전에는 {@code LineMonitoringController.dashboard}가 123줄 한 메서드에서 필터 정규화 +
 * 가시성 적용 + 페이징 + 행 변환 + 통계 + 정렬 href + 폼 보존을 모두 처리 — SRP 위반.
 * 본 빌더로 분리해 컨트롤러는 라우팅 + 파라미터 패킹만 담당하고, 빌더가 도메인/뷰 변환 전담.</p>
 *
 * <h3>모델 키</h3>
 * <ul>
 *   <li>{@code instances} — 페이지에 표시할 인스턴스 행 (Map view)</li>
 *   <li>{@code pagination} — {@link PaginationModel}이 만든 네비 객체</li>
 *   <li>{@code runningCount/completedCount/failedCount/totalCount} — 헤더 통계 (글로벌)</li>
 *   <li>{@code workflowName/instanceId/startDtFrom/startDtTo/selected*} — 폼 보존</li>
 *   <li>{@code sortBy/sortDir/sort* boolean} — 정렬 상태 표시</li>
 *   <li>{@code startDtSortHref/endDtSortHref/regDtSortHref} — 컬럼 헤더 토글 URL</li>
 *   <li>{@code advancedOpen} — Advanced 필터 영역 자동 펼침</li>
 *   <li>{@code autoRefresh/autoRefreshIntervalSeconds} — meta refresh 제어</li>
 * </ul>
 */
@Component
public class DashboardModelBuilder {

    /** Auto-refresh 간격(초) — 워커 폴링 주기 1초 + 여유. */
    private static final int AUTO_REFRESH_INTERVAL_SECONDS = 5;

    private final ActivityRepository activityRepository;
    private final LineDefinitionRepository definitionRepository;
    private final LineAclService aclService;

    public DashboardModelBuilder(ActivityRepository activityRepository,
                                 LineDefinitionRepository definitionRepository,
                                 LineAclService aclService) {
        this.activityRepository = activityRepository;
        this.definitionRepository = definitionRepository;
        this.aclService = aclService;
    }

    /**
     * 요청 파라미터를 model에 반영. 컨트롤러는 본 메서드 호출 후 view 이름만 반환하면 됨.
     *
     * @param req   파라미터 묶음
     * @param model Mustache view에 전달할 model — 본 메서드가 모든 키를 직접 채움
     */
    public void build(DashboardRequest req, Model model) {
        // auto-refresh 메타 — "1" 또는 "true" 모두 허용 (URL 친화)
        boolean autoRefresh = "1".equals(req.auto()) || "true".equalsIgnoreCase(req.auto());
        model.addAttribute("autoRefresh", autoRefresh);
        model.addAttribute("autoRefreshIntervalSeconds", AUTO_REFRESH_INTERVAL_SECONDS);

        int pageSize = PaginationModel.normalizeSize(req.size());

        // 날짜 양 끝 inclusive — from은 자정, to는 23:59:59 (사용자가 to 같은 날짜 선택 시 그 날 끝까지 포함)
        LocalDateTime startFromDt = req.startDtFrom() != null ? req.startDtFrom().atStartOfDay() : null;
        LocalDateTime startToDt = req.startDtTo() != null ? req.startDtTo().atTime(23, 59, 59) : null;

        // null/빈 statusSt 정규화 — 전부 빈 문자열이면 null로 떨어뜨려 SQL에서 무시
        List<String> normalizedStatuses = (req.statusSt() == null || req.statusSt().isEmpty())
                ? null
                : req.statusSt().stream().filter(s -> s != null && !s.isBlank()).toList();
        if (normalizedStatuses != null && normalizedStatuses.isEmpty()) {
            normalizedStatuses = null;
        }

        InstanceQueryFilter filter = new InstanceQueryFilter(
                req.workflowName(), normalizedStatuses, req.instanceId(),
                startFromDt, startToDt, req.sortBy(), req.sortDir());

        // ACL READ 가시성 필터 — ADMIN(null) 외엔 가시 workflow 이름만 노출
        Set<String> visibleNames = currentVisibleWorkflowNames();
        if (visibleNames != null) {
            filter = filter.withWorkflowNameAllowList(visibleNames);
        }

        long matchingCount = activityRepository.countInstances(filter);
        int totalPages = (matchingCount <= 0) ? 0 : (int) ((matchingCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(req.page(), totalPages);
        int offset = currPage * pageSize;

        List<LineInstance> rows = activityRepository.findInstancesPage(filter, offset, pageSize);
        model.addAttribute("instances", toInstanceViews(rows));

        // 폼 값 보존 — 사용자가 검색 조건을 유지한 채 페이지 이동 가능
        model.addAttribute("workflowName", req.workflowName());
        model.addAttribute("instanceId", req.instanceId());
        model.addAttribute("startDtFrom", req.startDtFrom() == null ? "" : req.startDtFrom().toString());
        model.addAttribute("startDtTo", req.startDtTo() == null ? "" : req.startDtTo().toString());

        // 다중 status 체크박스 selected 상태
        Set<String> selStatus = normalizedStatuses == null ? Set.of() : Set.copyOf(normalizedStatuses);
        model.addAttribute("selectedRunning", selStatus.contains("RUNNING"));
        model.addAttribute("selectedCompleted", selStatus.contains("COMPLETED"));
        model.addAttribute("selectedFailed", selStatus.contains("FAILED"));
        model.addAttribute("selectedTerminated", selStatus.contains("TERMINATED"));

        // 정렬 상태 — 컬럼 헤더에서 화살표 표시
        String effectiveSortBy = filter.sortBy();
        String effectiveSortDir = filter.sortDir();
        model.addAttribute("sortBy", effectiveSortBy);
        model.addAttribute("sortDir", effectiveSortDir);
        model.addAttribute("sortStartDt", "START_DT".equals(effectiveSortBy));
        model.addAttribute("sortEndDt", "END_DT".equals(effectiveSortBy));
        model.addAttribute("sortRegDt", "REG_DT".equals(effectiveSortBy));
        model.addAttribute("sortAsc", "ASC".equals(effectiveSortDir));

        // Advanced 필터 영역 자동 펼침 — 날짜/다중상태/정렬 중 하나라도 사용 시
        boolean hasAdvanced = (req.startDtFrom() != null || req.startDtTo() != null
                || (normalizedStatuses != null && !normalizedStatuses.isEmpty())
                || (req.sortBy() != null && !req.sortBy().isBlank()));
        model.addAttribute("advancedOpen", hasAdvanced);

        // 헤더 통계 — 필터와 무관한 글로벌 카운트 (GROUP BY 한 방, 가시 통계는 follow-up #159 비범위)
        Map<String, Long> byStatus = activityRepository.countInstancesByStatus();
        model.addAttribute("runningCount", byStatus.getOrDefault("RUNNING", 0L));
        model.addAttribute("completedCount", byStatus.getOrDefault("COMPLETED", 0L));
        model.addAttribute("failedCount", byStatus.getOrDefault("FAILED", 0L));
        long totalAll = byStatus.values().stream().mapToLong(Long::longValue).sum();
        model.addAttribute("totalCount", totalAll);
        model.addAttribute("navDashboard", true);

        // 페이지네이션 — 검색 폼 값 보존 (다중 status는 콤마-조인으로 한 키에 — Spring이 자동 split)
        Map<String, String> preserve = buildPreserveMap(req, normalizedStatuses, effectiveSortBy, effectiveSortDir);
        model.addAttribute("pagination",
                PaginationModel.build("/line/dashboard", currPage, pageSize, matchingCount, preserve));

        // 컬럼 헤더 sort 토글 href — sort 자체는 preserve에서 빼야 클릭 시 새 sort로 덮어쓰기 가능
        Map<String, String> sortPreserve = new LinkedHashMap<>(preserve);
        sortPreserve.remove("sortBy");
        sortPreserve.remove("sortDir");
        model.addAttribute("startDtSortHref", PaginationModel.toggleSortHref(
                "/line/dashboard", "START_DT", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("endDtSortHref", PaginationModel.toggleSortHref(
                "/line/dashboard", "END_DT", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("regDtSortHref", PaginationModel.toggleSortHref(
                "/line/dashboard", "REG_DT", effectiveSortBy, effectiveSortDir, sortPreserve));
    }

    /**
     * 인스턴스 엔티티 → Mustache view용 Map 변환. 상태별 badge CSS 클래스 부여.
     */
    private List<Map<String, Object>> toInstanceViews(List<LineInstance> rows) {
        return rows.stream().map(i -> {
            String badge = switch (i.statusSt() == null ? "" : i.statusSt()) {
                case "COMPLETED" -> "success";
                case "RUNNING" -> "warning";
                case "FAILED" -> "danger";
                default -> "secondary";
            };
            Map<String, Object> m = new HashMap<>();
            m.put("id", i.id());
            m.put("workflowName", i.workflowName());
            m.put("statusSt", i.statusSt());
            m.put("startDt", i.startDt());
            m.put("endDt", i.endDt());
            m.put("badgeClass", badge);
            return m;
        }).collect(Collectors.toList());
    }

    /** 페이지네이션 URL에 첨부할 폼 값 — 페이지/사이즈 변경 시 검색 조건 유지. */
    private Map<String, String> buildPreserveMap(DashboardRequest req,
                                                 List<String> normalizedStatuses,
                                                 String effectiveSortBy,
                                                 String effectiveSortDir) {
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", req.workflowName());
        if (normalizedStatuses != null && !normalizedStatuses.isEmpty()) {
            preserve.put("statusSt", String.join(",", normalizedStatuses));
        }
        preserve.put("instanceId", req.instanceId());
        if (req.startDtFrom() != null) {
            preserve.put("startDtFrom", req.startDtFrom().toString());
        }
        if (req.startDtTo() != null) {
            preserve.put("startDtTo", req.startDtTo().toString());
        }
        if (req.sortBy() != null && !req.sortBy().isBlank()) {
            preserve.put("sortBy", effectiveSortBy);
        }
        if (req.sortDir() != null && !req.sortDir().isBlank()) {
            preserve.put("sortDir", effectiveSortDir);
        }
        return preserve;
    }

    /**
     * ACL READ 가시성 필터에 사용할 활성 정의 → workflow_name 매핑 로드.
     * Active 정의 ≤ 10000 가정. ADMIN은 null 반환(필터 미적용).
     */
    private Set<String> currentVisibleWorkflowNames() {
        List<LineDefinition> active = definitionRepository.findActiveDefinitionsPage(0, 10000);
        return aclService.visibleWorkflowNames(active);
    }
}
