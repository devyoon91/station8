package com.station8.app.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.util.Dates;
import com.station8.app.util.PaginationModel;
import com.station8.engine.core.LineExecutor;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.DlqEntry;
import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.entity.LineStation;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.DlqRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/line")
public class LineMonitoringController {

    private final ActivityRepository activityRepository;
    private final LineExecutor workflowExecutor;
    private final DlqRepository dlqRepository;
    private final LineDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;
    private final com.station8.app.security.LineAclService aclService;
    private final com.station8.app.controller.dashboard.DashboardModelBuilder dashboardModelBuilder;
    private final com.station8.app.controller.dlq.DlqModelBuilder dlqModelBuilder;

    public LineMonitoringController(ActivityRepository activityRepository,
                                        LineExecutor workflowExecutor,
                                        DlqRepository dlqRepository,
                                        LineDefinitionRepository definitionRepository,
                                        ObjectMapper objectMapper,
                                        com.station8.app.security.LineAclService aclService,
                                        com.station8.app.controller.dashboard.DashboardModelBuilder dashboardModelBuilder,
                                        com.station8.app.controller.dlq.DlqModelBuilder dlqModelBuilder) {
        this.activityRepository = activityRepository;
        this.workflowExecutor = workflowExecutor;
        this.dlqRepository = dlqRepository;
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
        this.aclService = aclService;
        this.dashboardModelBuilder = dashboardModelBuilder;
        this.dlqModelBuilder = dlqModelBuilder;
    }

    /**
     * ACL READ 가시성 필터에 사용할 활성 정의 → workflow_name 매핑 로드 (DLQ 빌더에서 사용).
     * Active 정의 ≤ 10000 가정. ADMIN은 null 반환(필터 미적용).
     */
    private Set<String> currentVisibleWorkflowNames() {
        List<com.station8.engine.entity.LineDefinition> active =
                definitionRepository.findActiveDefinitionsPage(0, 10000);
        return aclService.visibleWorkflowNames(active);
    }

    /**
     * 전체 라인 인스턴스 목록 대시보드 — 모델 조립은 {@link com.station8.app.controller.dashboard.DashboardModelBuilder}.
     */
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
        // 컨트롤러는 라우팅 + 파라미터 패킹만 담당 — 모델 조립은 빌더에 위임 (#180)
        com.station8.app.controller.dashboard.DashboardRequest req =
                new com.station8.app.controller.dashboard.DashboardRequest(
                        workflowName, statusSt, instanceId,
                        startDtFrom, startDtTo,
                        sortBy, sortDir,
                        page, size, auto);
        dashboardModelBuilder.build(req, model);
        return "dashboard";
    }

    /**
     * 특정 인스턴스의 상세 실행 타임라인.
     *
     * <p>인스턴스가 존재하지 않거나 삭제된 경우 404로 응답한다 — 이전엔 NPE/EmptyResultDataAccessException
     * 으로 500이 떴음. 대시보드 목록은 있는데 클릭 직후 인스턴스가 사라진 race / 잘못된 URL
     * 직접 입력 / 다른 환경의 ID 복붙 등 모든 not-found 시나리오를 동일하게 처리.</p>
     */
    @GetMapping("/instance/{id}")
    public String timeline(@PathVariable("id") String instanceId, Model model) {
        LineInstance instance;
        try {
            instance = activityRepository.findInstanceById(instanceId);
        } catch (EmptyResultDataAccessException ex) {
            instance = null;
        }
        if (instance == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Instance not found: " + instanceId);
        }
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
            m.put("startDt", Dates.format(a.startDt()));
            m.put("endDt", Dates.format(a.endDt()));
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
     * DLQ 목록 조회 — 모델 조립은 {@link com.station8.app.controller.dlq.DlqModelBuilder}.
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
        com.station8.app.controller.dlq.DlqRequest req = new com.station8.app.controller.dlq.DlqRequest(
                workflowName, activityName, errorMessage, dlqStatusSt,
                failedAtFrom, failedAtTo, sortBy, sortDir, page, size);
        dlqModelBuilder.build(req, model);
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

