package com.station8.app.controller.dashboard;

import com.station8.app.security.LineAclService;
import com.station8.app.util.Dates;
import com.station8.app.util.PaginationModel;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.InstanceQueryFilter;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.time.LocalDate;
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

    /** #195 — 초기 진입 시 적용되는 기본 날짜 범위(일). 첫 인상 UX를 위한 합리적 default. */
    private static final int DEFAULT_RECENT_DAYS = 7;

    /** #195 진단 — 필터 좁힘으로 0건이 나왔을 때 빠른 확장 quick link 기간. */
    private static final int DIAGNOSTIC_BROADER_DAYS = 30;
    /** #195 진단 — "전체 기간" quick link의 시작일. 시드 데이터/실서비스 모두 2020 이후라 안전. */
    private static final String DIAGNOSTIC_ALL_TIME_FROM = "2020-01-01";

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

        // #195 — 초기 진입(둘 다 null) 시 최근 7일 default 적용. 사용자가 from/to 중 하나라도
        // 명시하면 사용자 의도 보호 차원에서 default 미적용. URL에는 default를 명시하지 않으므로
        // Reset 링크(/line/dashboard)는 그대로 default 재적용 의미.
        LocalDate effectiveFromDate = req.startDtFrom();
        LocalDate effectiveToDate = req.startDtTo();
        boolean appliedDateDefault = (effectiveFromDate == null && effectiveToDate == null);
        if (appliedDateDefault) {
            LocalDate today = LocalDate.now();
            effectiveFromDate = today.minusDays(DEFAULT_RECENT_DAYS);
            effectiveToDate = today;
        }

        // 날짜 양 끝 inclusive — from은 자정, to는 23:59:59 (사용자가 to 같은 날짜 선택 시 그 날 끝까지 포함)
        LocalDateTime startFromDt = effectiveFromDate != null ? effectiveFromDate.atStartOfDay() : null;
        LocalDateTime startToDt = effectiveToDate != null ? effectiveToDate.atTime(23, 59, 59) : null;

        // null/빈 statusSt 정규화 — 전부 빈 문자열이면 null로 떨어뜨려 SQL에서 무시
        List<String> normalizedStatuses = (req.statusSt() == null || req.statusSt().isEmpty())
                ? null
                : req.statusSt().stream().filter(s -> s != null && !s.isBlank()).toList();
        if (normalizedStatuses != null && normalizedStatuses.isEmpty()) {
            normalizedStatuses = null;
        }
        // #195 — status 미체크 = "전부" 의미. UI에서는 4개 모두 selected 표시해 사용자 혼동 제거.
        boolean noStatusFilter = (normalizedStatuses == null);

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

        // 폼 값 보존 — 사용자가 검색 조건을 유지한 채 페이지 이동 가능.
        // 날짜 input에는 effective 값을 표시 (default 적용 시에도 사용자가 적용된 범위를 시각적으로 인지)
        model.addAttribute("workflowName", req.workflowName());
        model.addAttribute("instanceId", req.instanceId());
        model.addAttribute("startDtFrom", effectiveFromDate == null ? "" : effectiveFromDate.toString());
        model.addAttribute("startDtTo", effectiveToDate == null ? "" : effectiveToDate.toString());
        model.addAttribute("appliedDateDefault", appliedDateDefault);

        // 다중 status 체크박스 selected 상태 — 미체크면 "전부" 의미라 4개 모두 selected 표시
        Set<String> selStatus = normalizedStatuses == null ? Set.of() : Set.copyOf(normalizedStatuses);
        model.addAttribute("selectedRunning", noStatusFilter || selStatus.contains("RUNNING"));
        model.addAttribute("selectedCompleted", noStatusFilter || selStatus.contains("COMPLETED"));
        model.addAttribute("selectedFailed", noStatusFilter || selStatus.contains("FAILED"));
        model.addAttribute("selectedTerminated", noStatusFilter || selStatus.contains("TERMINATED"));

        // 정렬 상태 — 컬럼 헤더에서 화살표 표시
        String effectiveSortBy = filter.sortBy();
        String effectiveSortDir = filter.sortDir();
        model.addAttribute("sortBy", effectiveSortBy);
        model.addAttribute("sortDir", effectiveSortDir);
        model.addAttribute("sortStartDt", "START_DT".equals(effectiveSortBy));
        model.addAttribute("sortEndDt", "END_DT".equals(effectiveSortBy));
        model.addAttribute("sortRegDt", "REG_DT".equals(effectiveSortBy));
        model.addAttribute("sortAsc", "ASC".equals(effectiveSortDir));

        // Advanced 필터 영역 자동 펼침 — 날짜/다중상태/정렬 중 하나라도 사용 시,
        // 또는 #195 default 적용 시(사용자가 적용된 7일 범위 + 안내문을 즉시 인지하도록)
        boolean hasAdvanced = (req.startDtFrom() != null || req.startDtTo() != null
                || (normalizedStatuses != null && !normalizedStatuses.isEmpty())
                || (req.sortBy() != null && !req.sortBy().isBlank()));
        model.addAttribute("advancedOpen", hasAdvanced || appliedDateDefault);

        // 헤더 통계 — 필터와 무관한 글로벌 카운트 (GROUP BY 한 방, 가시 통계는 follow-up #159 비범위)
        Map<String, Long> byStatus = activityRepository.countInstancesByStatus();
        model.addAttribute("runningCount", byStatus.getOrDefault("RUNNING", 0L));
        model.addAttribute("completedCount", byStatus.getOrDefault("COMPLETED", 0L));
        model.addAttribute("failedCount", byStatus.getOrDefault("FAILED", 0L));
        long totalAll = byStatus.values().stream().mapToLong(Long::longValue).sum();
        model.addAttribute("totalCount", totalAll);
        model.addAttribute("navDashboard", true);

        // #195 진단 — 빈 테이블 케이스: 전체는 있는데 필터로 0건이면 진단형 empty state 노출 트리거.
        model.addAttribute("matchingCount", matchingCount);
        model.addAttribute("filterEmptyDiagnostic", matchingCount == 0 && totalAll > 0);
        // 사용자에게 노출할 "현재 적용된 status" 라벨 — 전부면 "all", 일부면 콤마로 나열
        model.addAttribute("appliedStatusLabel",
                noStatusFilter ? "all" : String.join(", ", normalizedStatuses));

        // 진단 quick links — 헤더 통계엔 데이터가 있는데 테이블이 비었을 때 사용자가 즉시 확장 가능
        LocalDate today = LocalDate.now();
        LocalDate broaderFrom = today.minusDays(DIAGNOSTIC_BROADER_DAYS);
        model.addAttribute("quickLinkBroaderDays", DIAGNOSTIC_BROADER_DAYS);
        model.addAttribute("quickLinkBroader",
                "/line/dashboard?startDtFrom=" + broaderFrom + "&startDtTo=" + today);
        model.addAttribute("quickLinkAllTime",
                "/line/dashboard?startDtFrom=" + DIAGNOSTIC_ALL_TIME_FROM + "&startDtTo=" + today);

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
            m.put("startDt", Dates.format(i.startDt()));
            m.put("endDt", Dates.format(i.endDt()));
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
