package com.station8.app.controller.dlq;

import com.station8.app.security.LineAclService;
import com.station8.app.util.PaginationModel;
import com.station8.engine.entity.DlqEntry;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.repository.DlqQueryFilter;
import com.station8.engine.repository.DlqRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DLQ 페이지의 model 조립을 담당하는 빌더.
 *
 * <p>{@code LineMonitoringController.dlqList} 116줄 god method를 분리. 컨트롤러는 라우팅 +
 * 파라미터 패킹만 담당, 본 빌더가 필터 정규화 + 가시성 + 페이징 + 정렬 + 통계 + 폼 보존을 전담.</p>
 *
 * <h3>모델 키</h3>
 * <ul>
 *   <li>{@code dlqEntries} — 페이지에 표시할 DLQ 행 (Map view)</li>
 *   <li>{@code newCount/requeuedCount/discardedCount/totalDlqCount} — 헤더 통계</li>
 *   <li>{@code workflowName/activityName/errorMessage/failedAt*}, {@code selected*} — 폼 보존</li>
 *   <li>{@code sortBy/sortDir/sort* boolean} — 정렬 상태</li>
 *   <li>{@code failedAtSortHref/activitySortHref/regDtSortHref} — 컬럼 헤더 토글</li>
 *   <li>{@code pagination/advancedOpen} — 페이지 네비 + Advanced 펼침</li>
 * </ul>
 */
@Component
public class DlqModelBuilder {

    private final DlqRepository dlqRepository;
    private final LineDefinitionRepository definitionRepository;
    private final LineAclService aclService;

    public DlqModelBuilder(DlqRepository dlqRepository,
                           LineDefinitionRepository definitionRepository,
                           LineAclService aclService) {
        this.dlqRepository = dlqRepository;
        this.definitionRepository = definitionRepository;
        this.aclService = aclService;
    }

    /**
     * 요청 파라미터를 model에 반영. 컨트롤러는 본 메서드 호출 후 view 이름만 반환.
     *
     * @param req   파라미터 묶음
     * @param model Mustache view에 전달할 model
     */
    public void build(DlqRequest req, Model model) {
        int pageSize = PaginationModel.normalizeSize(req.size());

        // 날짜 양 끝 inclusive — to는 23:59:59까지 자동 확장
        LocalDateTime failedFromDt = req.failedAtFrom() != null ? req.failedAtFrom().atStartOfDay() : null;
        LocalDateTime failedToDt = req.failedAtTo() != null ? req.failedAtTo().atTime(23, 59, 59) : null;

        // null/빈 dlqStatusSt 정규화
        List<String> normalizedStatuses = (req.dlqStatusSt() == null || req.dlqStatusSt().isEmpty())
                ? null
                : req.dlqStatusSt().stream().filter(s -> s != null && !s.isBlank()).toList();
        if (normalizedStatuses != null && normalizedStatuses.isEmpty()) {
            normalizedStatuses = null;
        }

        DlqQueryFilter filter = new DlqQueryFilter(
                req.workflowName(), req.activityName(), req.errorMessage(),
                normalizedStatuses, failedFromDt, failedToDt,
                req.sortBy(), req.sortDir());

        // ACL READ 가시성 필터
        Set<String> visibleNames = currentVisibleWorkflowNames();
        if (visibleNames != null) {
            filter = filter.withWorkflowNameAllowList(visibleNames);
        }

        long totalCount = dlqRepository.count(filter);
        int totalPages = (totalCount <= 0) ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(req.page(), totalPages);

        List<DlqEntry> dlqEntries = dlqRepository.findPage(filter, currPage * pageSize, pageSize);
        model.addAttribute("dlqEntries", toDlqViews(dlqEntries));

        // 폼 값 보존
        model.addAttribute("workflowName", req.workflowName());
        model.addAttribute("activityName", req.activityName());
        model.addAttribute("errorMessage", req.errorMessage());
        model.addAttribute("failedAtFrom", req.failedAtFrom() == null ? "" : req.failedAtFrom().toString());
        model.addAttribute("failedAtTo", req.failedAtTo() == null ? "" : req.failedAtTo().toString());

        Set<String> selStatus = normalizedStatuses == null ? Set.of() : Set.copyOf(normalizedStatuses);
        model.addAttribute("selectedNew", selStatus.contains("NEW"));
        model.addAttribute("selectedRequeued", selStatus.contains("REQUEUED"));
        model.addAttribute("selectedDiscarded", selStatus.contains("DISCARDED"));

        // 정렬 상태
        String effectiveSortBy = filter.sortBy();
        String effectiveSortDir = filter.sortDir();
        model.addAttribute("sortBy", effectiveSortBy);
        model.addAttribute("sortDir", effectiveSortDir);
        model.addAttribute("sortFailedAt", "FAILED_AT_DT".equals(effectiveSortBy));
        model.addAttribute("sortActivity", "ACTIVITY_NAME".equals(effectiveSortBy));
        model.addAttribute("sortRegDt", "REG_DT".equals(effectiveSortBy));
        model.addAttribute("sortAsc", "ASC".equals(effectiveSortDir));

        boolean hasAdvanced = (req.failedAtFrom() != null || req.failedAtTo() != null
                || (normalizedStatuses != null && !normalizedStatuses.isEmpty())
                || (req.errorMessage() != null && !req.errorMessage().isBlank())
                || (req.sortBy() != null && !req.sortBy().isBlank()));
        model.addAttribute("advancedOpen", hasAdvanced);

        // 헤더 통계 — 글로벌 (가시 통계는 follow-up)
        Map<String, Long> byStatus = dlqRepository.countByStatus();
        model.addAttribute("newCount", byStatus.getOrDefault("NEW", 0L));
        model.addAttribute("requeuedCount", byStatus.getOrDefault("REQUEUED", 0L));
        model.addAttribute("discardedCount", byStatus.getOrDefault("DISCARDED", 0L));
        model.addAttribute("totalDlqCount", totalCount);

        // 페이지네이션 폼 보존
        Map<String, String> preserve = buildPreserveMap(req, normalizedStatuses, effectiveSortBy, effectiveSortDir);
        model.addAttribute("pagination",
                PaginationModel.build("/line/dlq", currPage, pageSize, totalCount, preserve));

        // 컬럼 헤더 sort 토글 — sort 자체는 preserve에서 빼야 클릭 시 새 sort로 덮기 가능
        Map<String, String> sortPreserve = new LinkedHashMap<>(preserve);
        sortPreserve.remove("sortBy");
        sortPreserve.remove("sortDir");
        model.addAttribute("failedAtSortHref", PaginationModel.toggleSortHref(
                "/line/dlq", "FAILED_AT_DT", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("activitySortHref", PaginationModel.toggleSortHref(
                "/line/dlq", "ACTIVITY_NAME", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("regDtSortHref", PaginationModel.toggleSortHref(
                "/line/dlq", "REG_DT", effectiveSortBy, effectiveSortDir, sortPreserve));

        model.addAttribute("navDlq", true);
    }

    /**
     * DlqEntry → Mustache view용 Map. 상태별 badge CSS 클래스 부여.
     */
    private List<Map<String, Object>> toDlqViews(List<DlqEntry> entries) {
        return entries.stream().map(e -> {
            String badge = switch (e.dlqStatusSt() == null ? "" : e.dlqStatusSt()) {
                case "NEW" -> "danger";
                case "REQUEUED" -> "info";
                default -> "mute";
            };
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.id());
            m.put("workflowName", e.workflowName());
            m.put("activityName", e.activityName());
            m.put("dlqStatusSt", e.dlqStatusSt());
            m.put("retryCnt", e.retryCnt());
            m.put("maxRetryCnt", e.maxRetryCnt());
            m.put("failedAtDt", e.failedAtDt());
            m.put("isNew", "NEW".equals(e.dlqStatusSt()));
            m.put("badgeClass", badge);
            return m;
        }).toList();
    }

    /** 페이지네이션 URL preserve — 페이지/사이즈 변경 시 검색 조건 유지. */
    private Map<String, String> buildPreserveMap(DlqRequest req,
                                                 List<String> normalizedStatuses,
                                                 String effectiveSortBy,
                                                 String effectiveSortDir) {
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", req.workflowName());
        preserve.put("activityName", req.activityName());
        preserve.put("errorMessage", req.errorMessage());
        if (normalizedStatuses != null && !normalizedStatuses.isEmpty()) {
            preserve.put("dlqStatusSt", String.join(",", normalizedStatuses));
        }
        if (req.failedAtFrom() != null) {
            preserve.put("failedAtFrom", req.failedAtFrom().toString());
        }
        if (req.failedAtTo() != null) {
            preserve.put("failedAtTo", req.failedAtTo().toString());
        }
        if (req.sortBy() != null && !req.sortBy().isBlank()) {
            preserve.put("sortBy", effectiveSortBy);
        }
        if (req.sortDir() != null && !req.sortDir().isBlank()) {
            preserve.put("sortDir", effectiveSortDir);
        }
        return preserve;
    }

    /** ACL READ 가시성 필터용 workflow_name 매핑 — ADMIN은 null 반환. */
    private Set<String> currentVisibleWorkflowNames() {
        List<LineDefinition> active = definitionRepository.findActiveDefinitionsPage(0, 10000);
        return aclService.visibleWorkflowNames(active);
    }
}
