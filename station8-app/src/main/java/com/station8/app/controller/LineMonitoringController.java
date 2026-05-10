package com.station8.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.util.PaginationModel;
import com.station8.engine.core.LineExecutor;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.DlqEntry;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineStation;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.DlqQueryFilter;
import com.station8.engine.repository.DlqRepository;
import com.station8.engine.repository.InstanceQueryFilter;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/line")
public class LineMonitoringController {

    private final ActivityRepository activityRepository;
    private final LineExecutor workflowExecutor;
    private final DlqRepository dlqRepository;
    private final LineDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    public LineMonitoringController(ActivityRepository activityRepository,
                                        LineExecutor workflowExecutor,
                                        DlqRepository dlqRepository,
                                        LineDefinitionRepository definitionRepository,
                                        ObjectMapper objectMapper) {
        this.activityRepository = activityRepository;
        this.workflowExecutor = workflowExecutor;
        this.dlqRepository = dlqRepository;
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 전체 라인 인스턴스 목록 대시보드 (#97 페이징 적용).
     * 필터/페이징은 SQL로 내려가며, 헤더 통계 카드는 ``GROUP BY STATUS_ST`` 한 방.
     */
    /** Auto-refresh interval (seconds) when {@code ?auto=1} (#100). 워커 폴링 주기 1초 + 여유. */
    private static final int AUTO_REFRESH_INTERVAL_SECONDS = 5;

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(value = "workflowName", required = false) String workflowName,
                            @RequestParam(value = "statusSt", required = false) List<String> statusSt,
                            @RequestParam(value = "instanceId", required = false) String instanceId,
                            @RequestParam(value = "startDtFrom", required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDtFrom,
                            @RequestParam(value = "startDtTo", required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDtTo,
                            @RequestParam(value = "sortBy", required = false) String sortBy,
                            @RequestParam(value = "sortDir", required = false) String sortDir,
                            @RequestParam(value = "page", required = false) Integer page,
                            @RequestParam(value = "size", required = false) Integer size,
                            @RequestParam(value = "auto", required = false) String auto,
                            Model model) {
        boolean autoRefresh = "1".equals(auto) || "true".equalsIgnoreCase(auto);
        model.addAttribute("autoRefresh", autoRefresh);
        model.addAttribute("autoRefreshIntervalSeconds", AUTO_REFRESH_INTERVAL_SECONDS);
        int pageSize = PaginationModel.normalizeSize(size);

        // #137 — D5 inclusive 양 끝: from은 자정, to는 23:59:59
        LocalDateTime startFromDt = startDtFrom != null ? startDtFrom.atStartOfDay() : null;
        LocalDateTime startToDt = startDtTo != null ? startDtTo.atTime(23, 59, 59) : null;

        // null/빈 statusSt 정규화 (D2 다중 status)
        List<String> normalizedStatuses = (statusSt == null || statusSt.isEmpty())
                ? null
                : statusSt.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (normalizedStatuses != null && normalizedStatuses.isEmpty()) normalizedStatuses = null;

        InstanceQueryFilter filter = new InstanceQueryFilter(
                workflowName, normalizedStatuses, instanceId,
                startFromDt, startToDt, sortBy, sortDir);

        long matchingCount = activityRepository.countInstances(filter);
        int totalPages = (matchingCount <= 0) ? 0 : (int) ((matchingCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(page, totalPages);
        int offset = currPage * pageSize;

        List<LineInstance> rows = activityRepository.findInstancesPage(filter, offset, pageSize);

        List<java.util.Map<String, Object>> instanceViews = rows.stream().map(i -> {
            String badge = switch (i.statusSt() == null ? "" : i.statusSt()) {
                case "COMPLETED" -> "success";
                case "RUNNING" -> "warning";
                case "FAILED" -> "danger";
                default -> "secondary";
            };
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", i.id());
            m.put("workflowName", i.workflowName());
            m.put("statusSt", i.statusSt());
            m.put("startDt", i.startDt());
            m.put("endDt", i.endDt());
            m.put("badgeClass", badge);
            return m;
        }).collect(Collectors.toList());
        model.addAttribute("instances", instanceViews);

        // 폼 값 보존 (값이 있으면 input value로 노출)
        model.addAttribute("workflowName", workflowName);
        model.addAttribute("instanceId", instanceId);
        model.addAttribute("startDtFrom", startDtFrom == null ? "" : startDtFrom.toString());
        model.addAttribute("startDtTo", startDtTo == null ? "" : startDtTo.toString());
        // 다중 status 체크박스 selected 상태
        Set<String> selStatus = normalizedStatuses == null ? Set.of() : Set.copyOf(normalizedStatuses);
        model.addAttribute("selectedRunning", selStatus.contains("RUNNING"));
        model.addAttribute("selectedCompleted", selStatus.contains("COMPLETED"));
        model.addAttribute("selectedFailed", selStatus.contains("FAILED"));
        model.addAttribute("selectedTerminated", selStatus.contains("TERMINATED"));
        // 정렬 상태 — 컬럼 헤더에서 화살표 표시 (D3=b 헤더 클릭 정렬)
        String effectiveSortBy = filter.sortBy();
        String effectiveSortDir = filter.sortDir();
        model.addAttribute("sortBy", effectiveSortBy);
        model.addAttribute("sortDir", effectiveSortDir);
        model.addAttribute("sortStartDt", "START_DT".equals(effectiveSortBy));
        model.addAttribute("sortEndDt", "END_DT".equals(effectiveSortBy));
        model.addAttribute("sortRegDt", "REG_DT".equals(effectiveSortBy));
        model.addAttribute("sortAsc", "ASC".equals(effectiveSortDir));
        // Advanced 필터 영역이 펼쳐진 상태인지 (날짜/다중상태/정렬 중 하나라도 사용 시 자동 펼침)
        boolean hasAdvanced = (startDtFrom != null || startDtTo != null
                || (normalizedStatuses != null && !normalizedStatuses.isEmpty())
                || (sortBy != null && !sortBy.isBlank()));
        model.addAttribute("advancedOpen", hasAdvanced);

        // 헤더 통계 — 필터와 무관한 글로벌 카운트 (GROUP BY 한 방)
        Map<String, Long> byStatus = activityRepository.countInstancesByStatus();
        model.addAttribute("runningCount", byStatus.getOrDefault("RUNNING", 0L));
        model.addAttribute("completedCount", byStatus.getOrDefault("COMPLETED", 0L));
        model.addAttribute("failedCount", byStatus.getOrDefault("FAILED", 0L));
        long totalAll = byStatus.values().stream().mapToLong(Long::longValue).sum();
        model.addAttribute("totalCount", totalAll);
        model.addAttribute("navDashboard", true);

        // 페이지네이션 — 검색 폼 값 보존 (다중 status는 콤마-조인으로 한 키에 — Spring이 자동 split)
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", workflowName);
        if (normalizedStatuses != null && !normalizedStatuses.isEmpty()) {
            preserve.put("statusSt", String.join(",", normalizedStatuses));
        }
        preserve.put("instanceId", instanceId);
        if (startDtFrom != null) preserve.put("startDtFrom", startDtFrom.toString());
        if (startDtTo != null) preserve.put("startDtTo", startDtTo.toString());
        if (sortBy != null && !sortBy.isBlank()) preserve.put("sortBy", effectiveSortBy);
        if (sortDir != null && !sortDir.isBlank()) preserve.put("sortDir", effectiveSortDir);
        model.addAttribute("pagination",
                PaginationModel.build("/line/dashboard", currPage, pageSize, matchingCount, preserve));

        // #137 — 컬럼 헤더용 sort href (클릭 시 정렬 토글)
        Map<String, String> sortPreserve = new LinkedHashMap<>(preserve);
        sortPreserve.remove("sortBy");
        sortPreserve.remove("sortDir");
        model.addAttribute("startDtSortHref",
                buildSortHref("/line/dashboard", "START_DT", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("endDtSortHref",
                buildSortHref("/line/dashboard", "END_DT", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("regDtSortHref",
                buildSortHref("/line/dashboard", "REG_DT", effectiveSortBy, effectiveSortDir, sortPreserve));

        return "dashboard";
    }

    /**
     * #137 — 컬럼 헤더 클릭용 정렬 href 생성.
     * 같은 컬럼 재클릭 시 방향 토글, 다른 컬럼 첫 클릭은 DESC (D8=b).
     */
    private static String buildSortHref(String base, String column,
                                        String currentSortBy, String currentSortDir,
                                        Map<String, String> preserveQuery) {
        String newDir = column.equals(currentSortBy)
                ? ("ASC".equals(currentSortDir) ? "DESC" : "ASC")
                : "DESC";
        StringBuilder sb = new StringBuilder(base)
                .append("?sortBy=").append(column)
                .append("&sortDir=").append(newDir);
        for (Map.Entry<String, String> e : preserveQuery.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isBlank()) continue;
            sb.append('&').append(e.getKey()).append('=')
                    .append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 특정 인스턴스의 상세 실행 타임라인
     */
    @GetMapping("/instance/{id}")
    public String timeline(@PathVariable("id") String instanceId, Model model) {
        LineInstance instance = activityRepository.findInstanceById(instanceId);
        List<ActivityExecution> activities = activityRepository.findActivitiesByInstanceId(instanceId);

        // Mustache view용 — derived 필드를 미리 계산
        java.util.Map<String, Object> instView = new java.util.HashMap<>();
        instView.put("id", instance.id());
        instView.put("workflowName", instance.workflowName());
        instView.put("statusSt", instance.statusSt());
        instView.put("inputData", instance.inputData());
        instView.put("badgeClass", badgeFor(instance.statusSt()));
        instView.put("isFailed", "FAILED".equals(instance.statusSt()));
        instView.put("isRunning", "RUNNING".equals(instance.statusSt()));
        instView.put("isTerminated", "TERMINATED".equals(instance.statusSt()));
        instView.put("isPaused", "PAUSED".equals(instance.statusSt()));  // #139
        model.addAttribute("instance", instView);

        List<java.util.Map<String, Object>> actViews = activities.stream().map(a -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", a.id());
            m.put("nodeId", a.nodeId());
            m.put("activityName", a.activityName());
            m.put("statusSt", a.statusSt());
            m.put("startDt", a.startDt());
            m.put("endDt", a.endDt());
            m.put("inputData", a.inputData());
            m.put("outputData", a.outputData());
            m.put("errorMessage", a.errorMessage());
            m.put("retryCnt", a.retryCnt());
            m.put("hasRetry", a.retryCnt() > 0);
            m.put("badgeClass", badgeFor(a.statusSt()));
            m.put("dotClass", dotFor(a.statusSt()));
            m.put("isActivityFailed", "FAILED".equals(a.statusSt()));  // #139 — activity retry 버튼용
            return m;
        }).toList();
        model.addAttribute("activities", actViews);
        model.addAttribute("navDashboard", true);

        // #87 M2 — 인스턴스 진행 위치 오버레이용 노선도 페이로드
        String subwayJson = buildSubwayPayload(instanceId, activities);
        if (subwayJson != null) {
            model.addAttribute("subwayJson", subwayJson);
            model.addAttribute("hasSubway", true);
        } else {
            model.addAttribute("hasSubway", false);
        }

        return "timeline";
    }

    /**
     * 인스턴스의 액티비티 실행 이력에서 ``nodeId``를 통해 라인 정의를 역조회하고,
     * 노선도 SVG 렌더에 필요한 JSON({nodes, edges, statusByNode})을 만든다.
     *
     * 레거시 모드(executions의 ``nodeId``가 모두 null)이면 ``null``을 반환하고,
     * view에서는 노선도 없이 timeline만 보여준다.
     *
     * 액티비티 상태 → 서브웨이 상태 매핑:
     * <ul>
     *   <li>WAITING_DEPENDENCIES, PENDING → ``pending``</li>
     *   <li>RUNNING                       → ``running``  (점멸 후광)</li>
     *   <li>COMPLETED                     → ``completed`` (채움)</li>
     *   <li>FAILED                        → ``failed`` (적색 채움)</li>
     *   <li>실행 기록 없음                  → ``untouched`` (기본 외곽선)</li>
     * </ul>
     */
    private String buildSubwayPayload(String instanceId, List<ActivityExecution> activities) {
        String anchorNodeId = activities.stream()
                .map(ActivityExecution::nodeId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
        if (anchorNodeId == null) return null;

        String definitionId = definitionRepository.findDefinitionIdByNodeId(anchorNodeId);
        if (definitionId == null) return null;

        List<LineStation> nodes = definitionRepository.findNodesByDefinition(definitionId);
        if (nodes.isEmpty()) return null;
        List<LineTrack> edges = definitionRepository.findEdgesByDefinition(definitionId);

        Map<String, String> statusByNode = InstanceStateBuilder.buildStatusByNode(activities);

        Map<String, Object> payload = new HashMap<>();
        payload.put("definitionId", definitionId);
        payload.put("instanceId", instanceId);
        payload.put("nodes", nodes.stream().map(n -> Map.of(
                "id", n.id(),
                "name", n.nodeNm() == null ? n.activityNm() : n.nodeNm(),
                "activity", n.activityNm() == null ? "" : n.activityNm(),
                "x", n.posXNo() == null ? 0 : n.posXNo(),
                "y", n.posYNo() == null ? 0 : n.posYNo()
        )).toList());
        payload.put("edges", edges.stream().map(e -> Map.of(
                "id", e.id(),
                "from", e.fromNodeId(),
                "to", e.toNodeId()
        )).toList());
        payload.put("statusByNode", statusByNode);

        try {
            return objectMapper.writeValueAsString(payload).replace("</", "<\\/");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("subway payload 직렬화 실패", ex);
        }
    }

    private static String badgeFor(String status) {
        return InstanceStateBuilder.badgeForInstanceStatus(status);
    }

    private static String dotFor(String status) {
        return InstanceStateBuilder.dotForActivityStatus(status);
    }

    /**
     * 실패한 라인 재개. #140 — EXECUTE 권한 필요.
     */
    @PostMapping("/instance/{id}/resume")
    @PreAuthorize("@lineAcl.canExecuteInstance(#instanceId)")
    public String resume(@PathVariable("id") String instanceId) {
        workflowExecutor.resumeLine(instanceId);
        return "redirect:/line/instance/" + instanceId;
    }

    /**
     * RUNNING 인스턴스 강제 종료 (#101). 인스턴스 + 시작 안 한 액티비티들을 TERMINATED로 전이.
     * RUNNING 액티비티는 워커 자연 완료 — 인스턴스가 TERMINATED라 후행 fan-out은 차단됨.
     */
    @PostMapping("/instance/{id}/terminate")
    @PreAuthorize("@lineAcl.canExecuteInstance(#instanceId)")
    public String terminate(@PathVariable("id") String instanceId,
                            org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        try {
            workflowExecutor.terminateLine(instanceId);
            flash.addFlashAttribute("terminateMsg", "[OK] 인스턴스 종료 요청 완료 — 시작 안 한 액티비티는 TERMINATED, 진행 중은 자연 완료 후 후행 차단");
            flash.addFlashAttribute("terminateOk", true);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("terminateMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("terminateOk", false);
        }
        return "redirect:/line/instance/" + instanceId;
    }

    /** #139 — RUNNING 인스턴스 일시 정지. PENDING 활동은 워커 폴링이 차단, RUNNING은 자연 완료. */
    @PostMapping("/instance/{id}/pause")
    @PreAuthorize("@lineAcl.canExecuteInstance(#instanceId)")
    public String pause(@PathVariable("id") String instanceId,
                        org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        try {
            workflowExecutor.pauseLine(instanceId);
            flash.addFlashAttribute("terminateMsg", "[OK] 일시 정지 — PAUSED. 활동 폴링 차단됨, RUNNING 활동은 자연 완료.");
            flash.addFlashAttribute("terminateOk", true);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("terminateMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("terminateOk", false);
        }
        return "redirect:/line/instance/" + instanceId;
    }

    /** #139 — PAUSED 인스턴스 재개. 일시정지 동안 완료된 활동의 fan-out 재평가. */
    @PostMapping("/instance/{id}/unpause")
    @PreAuthorize("@lineAcl.canExecuteInstance(#instanceId)")
    public String unpause(@PathVariable("id") String instanceId,
                          org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        try {
            workflowExecutor.unpauseLine(instanceId);
            flash.addFlashAttribute("terminateMsg", "[OK] 재개 — RUNNING. fan-out 재평가 완료.");
            flash.addFlashAttribute("terminateOk", true);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("terminateMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("terminateOk", false);
        }
        return "redirect:/line/instance/" + instanceId;
    }

    /** #139 — 단일 FAILED 활동만 PENDING으로 reset (활동 단위 retry). */
    @PostMapping("/instance/{id}/activity/{execId}/retry")
    @PreAuthorize("@lineAcl.canExecuteInstance(#instanceId)")
    public String retryActivity(@PathVariable("id") String instanceId,
                                @PathVariable("execId") String activityExecutionId,
                                org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        try {
            workflowExecutor.retryActivity(activityExecutionId);
            flash.addFlashAttribute("terminateMsg", "[OK] 활동 retry — PENDING으로 reset됨.");
            flash.addFlashAttribute("terminateOk", true);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("terminateMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("terminateOk", false);
        }
        return "redirect:/line/instance/" + instanceId;
    }

    /**
     * DLQ 목록 조회 (#97 페이징 + #137 필터 강화).
     */
    @GetMapping("/dlq")
    public String dlqList(@RequestParam(value = "workflowName", required = false) String workflowName,
                          @RequestParam(value = "activityName", required = false) String activityName,
                          @RequestParam(value = "errorMessage", required = false) String errorMessage,
                          @RequestParam(value = "dlqStatusSt", required = false) List<String> dlqStatusSt,
                          @RequestParam(value = "failedAtFrom", required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate failedAtFrom,
                          @RequestParam(value = "failedAtTo", required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate failedAtTo,
                          @RequestParam(value = "sortBy", required = false) String sortBy,
                          @RequestParam(value = "sortDir", required = false) String sortDir,
                          @RequestParam(value = "page", required = false) Integer page,
                          @RequestParam(value = "size", required = false) Integer size,
                          Model model) {
        int pageSize = PaginationModel.normalizeSize(size);

        LocalDateTime failedFromDt = failedAtFrom != null ? failedAtFrom.atStartOfDay() : null;
        LocalDateTime failedToDt = failedAtTo != null ? failedAtTo.atTime(23, 59, 59) : null;
        List<String> normalizedStatuses = (dlqStatusSt == null || dlqStatusSt.isEmpty())
                ? null
                : dlqStatusSt.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (normalizedStatuses != null && normalizedStatuses.isEmpty()) normalizedStatuses = null;

        DlqQueryFilter filter = new DlqQueryFilter(
                workflowName, activityName, errorMessage,
                normalizedStatuses, failedFromDt, failedToDt,
                sortBy, sortDir);

        long totalCount = dlqRepository.count(filter);
        int totalPages = (totalCount <= 0) ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(page, totalPages);

        List<DlqEntry> dlqEntries = dlqRepository.findPage(filter, currPage * pageSize, pageSize);
        List<java.util.Map<String, Object>> view = dlqEntries.stream().map(e -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", e.id());
            m.put("workflowName", e.workflowName());
            m.put("activityName", e.activityName());
            m.put("dlqStatusSt", e.dlqStatusSt());
            m.put("retryCnt", e.retryCnt());
            m.put("maxRetryCnt", e.maxRetryCnt());
            m.put("failedAtDt", e.failedAtDt());
            m.put("isNew", "NEW".equals(e.dlqStatusSt()));
            String badge = switch (e.dlqStatusSt() == null ? "" : e.dlqStatusSt()) {
                case "NEW" -> "danger";
                case "REQUEUED" -> "info";
                default -> "mute";
            };
            m.put("badgeClass", badge);
            return m;
        }).toList();
        model.addAttribute("dlqEntries", view);

        // 폼 값 보존
        model.addAttribute("workflowName", workflowName);
        model.addAttribute("activityName", activityName);
        model.addAttribute("errorMessage", errorMessage);
        model.addAttribute("failedAtFrom", failedAtFrom == null ? "" : failedAtFrom.toString());
        model.addAttribute("failedAtTo", failedAtTo == null ? "" : failedAtTo.toString());
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
        boolean hasAdvanced = (failedAtFrom != null || failedAtTo != null
                || (normalizedStatuses != null && !normalizedStatuses.isEmpty())
                || (errorMessage != null && !errorMessage.isBlank())
                || (sortBy != null && !sortBy.isBlank()));
        model.addAttribute("advancedOpen", hasAdvanced);

        Map<String, Long> byStatus = dlqRepository.countByStatus();
        model.addAttribute("newCount", byStatus.getOrDefault("NEW", 0L));
        model.addAttribute("requeuedCount", byStatus.getOrDefault("REQUEUED", 0L));
        model.addAttribute("discardedCount", byStatus.getOrDefault("DISCARDED", 0L));
        model.addAttribute("totalDlqCount", totalCount);

        // 페이지네이션 폼 보존
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", workflowName);
        preserve.put("activityName", activityName);
        preserve.put("errorMessage", errorMessage);
        if (normalizedStatuses != null && !normalizedStatuses.isEmpty()) {
            preserve.put("dlqStatusSt", String.join(",", normalizedStatuses));
        }
        if (failedAtFrom != null) preserve.put("failedAtFrom", failedAtFrom.toString());
        if (failedAtTo != null) preserve.put("failedAtTo", failedAtTo.toString());
        if (sortBy != null && !sortBy.isBlank()) preserve.put("sortBy", effectiveSortBy);
        if (sortDir != null && !sortDir.isBlank()) preserve.put("sortDir", effectiveSortDir);
        model.addAttribute("pagination",
                PaginationModel.build("/line/dlq", currPage, pageSize, totalCount, preserve));

        // #137 — DLQ 컬럼 헤더 sort href
        Map<String, String> sortPreserve = new LinkedHashMap<>(preserve);
        sortPreserve.remove("sortBy");
        sortPreserve.remove("sortDir");
        model.addAttribute("failedAtSortHref",
                buildSortHref("/line/dlq", "FAILED_AT_DT", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("activitySortHref",
                buildSortHref("/line/dlq", "ACTIVITY_NAME", effectiveSortBy, effectiveSortDir, sortPreserve));
        model.addAttribute("regDtSortHref",
                buildSortHref("/line/dlq", "REG_DT", effectiveSortBy, effectiveSortDir, sortPreserve));

        model.addAttribute("navDlq", true);
        return "dlq";
    }

    /**
     * DLQ 항목 상세 조회
     */
    @GetMapping("/dlq/{id}")
    public String dlqDetail(@PathVariable("id") String dlqId, Model model) {
        DlqEntry entry = dlqRepository.findById(dlqId);
        java.util.Map<String, Object> view = new java.util.HashMap<>();
        view.put("id", entry.id());
        view.put("workflowName", entry.workflowName());
        view.put("activityName", entry.activityName());
        view.put("dlqStatusSt", entry.dlqStatusSt());
        view.put("instanceId", entry.instanceId());
        view.put("executionId", entry.executionId());
        view.put("retryCnt", entry.retryCnt());
        view.put("maxRetryCnt", entry.maxRetryCnt());
        view.put("failedAtDt", entry.failedAtDt());
        view.put("regDt", entry.regDt());
        view.put("errorMessage", entry.errorMessage());
        view.put("stackTrace", entry.stackTrace());
        view.put("isNew", "NEW".equals(entry.dlqStatusSt()));
        model.addAttribute("entry", view);
        model.addAttribute("navDlq", true);
        return "dlq-detail";
    }

    /**
     * DLQ 항목 재처리 (Requeue): 동일 액티비티를 새 PENDING으로 생성
     */
    @PostMapping("/dlq/{id}/requeue")
    public String dlqRequeue(@PathVariable("id") String dlqId) {
        DlqEntry entry = dlqRepository.findById(dlqId);
        activityRepository.createPending(entry.instanceId(), entry.activityName(), null, null);
        dlqRepository.updateStatus(dlqId, "REQUEUED");
        return "redirect:/line/dlq";
    }

    /**
     * DLQ 항목 폐기 (Discard): DLQ 레코드 상태만 업데이트
     */
    @PostMapping("/dlq/{id}/discard")
    public String dlqDiscard(@PathVariable("id") String dlqId) {
        dlqRepository.updateStatus(dlqId, "DISCARDED");
        return "redirect:/line/dlq";
    }
}

