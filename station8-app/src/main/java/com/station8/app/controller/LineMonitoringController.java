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
import com.station8.engine.repository.DlqRepository;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(value = "workflowName", required = false) String workflowName,
                            @RequestParam(value = "statusSt", required = false) String statusSt,
                            @RequestParam(value = "instanceId", required = false) String instanceId,
                            @RequestParam(value = "page", required = false) Integer page,
                            @RequestParam(value = "size", required = false) Integer size,
                            Model model) {
        int pageSize = PaginationModel.normalizeSize(size);

        long matchingCount = activityRepository.countInstances(workflowName, statusSt, instanceId);
        int totalPages = (matchingCount <= 0) ? 0 : (int) ((matchingCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(page, totalPages);
        int offset = currPage * pageSize;

        List<LineInstance> rows = activityRepository.findInstancesPage(
                workflowName, statusSt, instanceId, offset, pageSize);

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

        model.addAttribute("workflowName", workflowName);
        model.addAttribute("statusSt", statusSt);
        model.addAttribute("instanceId", instanceId);
        model.addAttribute("selectedRunning", "RUNNING".equals(statusSt));
        model.addAttribute("selectedCompleted", "COMPLETED".equals(statusSt));
        model.addAttribute("selectedFailed", "FAILED".equals(statusSt));
        model.addAttribute("selectedTerminated", "TERMINATED".equals(statusSt));

        // 헤더 통계 — 필터와 무관한 글로벌 카운트 (GROUP BY 한 방)
        Map<String, Long> byStatus = activityRepository.countInstancesByStatus();
        model.addAttribute("runningCount", byStatus.getOrDefault("RUNNING", 0L));
        model.addAttribute("completedCount", byStatus.getOrDefault("COMPLETED", 0L));
        model.addAttribute("failedCount", byStatus.getOrDefault("FAILED", 0L));
        long totalAll = byStatus.values().stream().mapToLong(Long::longValue).sum();
        model.addAttribute("totalCount", totalAll);
        model.addAttribute("navDashboard", true);

        // 페이지네이션 — 검색 폼 값 보존
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", workflowName);
        preserve.put("statusSt", statusSt);
        preserve.put("instanceId", instanceId);
        model.addAttribute("pagination",
                PaginationModel.build("/line/dashboard", currPage, pageSize, matchingCount, preserve));

        return "dashboard";
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
        model.addAttribute("instance", instView);

        List<java.util.Map<String, Object>> actViews = activities.stream().map(a -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
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

        Map<String, String> statusByNode = new HashMap<>();
        for (ActivityExecution a : activities) {
            if (a.nodeId() == null) continue;
            String mapped = mapActivityStatus(a.statusSt());
            // 같은 역에 다회 실행이 있으면 더 진행된 상태가 덮어쓴다
            String prev = statusByNode.get(a.nodeId());
            if (prev == null || rank(mapped) > rank(prev)) {
                statusByNode.put(a.nodeId(), mapped);
            }
        }

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

    private static String mapActivityStatus(String s) {
        return switch (s == null ? "" : s) {
            case "COMPLETED" -> "completed";
            case "RUNNING" -> "running";
            case "FAILED" -> "failed";
            case "PENDING", "WAITING_DEPENDENCIES" -> "pending";
            default -> "untouched";
        };
    }

    /** 동일 역 다회 실행 시 더 진행된 상태로 덮어쓰기 위한 순서. */
    private static int rank(String status) {
        return switch (status) {
            case "untouched" -> 0;
            case "pending"   -> 1;
            case "running"   -> 2;
            case "failed"    -> 3;
            case "completed" -> 4;
            default          -> 0;
        };
    }

    private static String badgeFor(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "success";
            case "RUNNING" -> "warning";
            case "FAILED" -> "danger";
            default -> "mute";
        };
    }

    private static String dotFor(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "completed";
            case "RUNNING" -> "running";
            case "FAILED" -> "failed";
            default -> "pending";
        };
    }

    /**
     * 실패한 라인 재개
     */
    @PostMapping("/instance/{id}/resume")
    public String resume(@PathVariable("id") String instanceId) {
        workflowExecutor.resumeLine(instanceId);
        return "redirect:/line/instance/" + instanceId;
    }

    /**
     * RUNNING 인스턴스 강제 종료 (#101). 인스턴스 + 시작 안 한 액티비티들을 TERMINATED로 전이.
     * RUNNING 액티비티는 워커 자연 완료 — 인스턴스가 TERMINATED라 후행 fan-out은 차단됨.
     */
    @PostMapping("/instance/{id}/terminate")
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

    /**
     * DLQ 목록 조회 (#97 페이징 적용).
     */
    @GetMapping("/dlq")
    public String dlqList(@RequestParam(value = "page", required = false) Integer page,
                          @RequestParam(value = "size", required = false) Integer size,
                          Model model) {
        int pageSize = PaginationModel.normalizeSize(size);
        long totalCount = dlqRepository.count();
        int totalPages = (totalCount <= 0) ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(page, totalPages);

        List<DlqEntry> dlqEntries = dlqRepository.findPage(currPage * pageSize, pageSize);
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

        Map<String, Long> byStatus = dlqRepository.countByStatus();
        model.addAttribute("newCount", byStatus.getOrDefault("NEW", 0L));
        model.addAttribute("requeuedCount", byStatus.getOrDefault("REQUEUED", 0L));
        model.addAttribute("discardedCount", byStatus.getOrDefault("DISCARDED", 0L));
        model.addAttribute("totalDlqCount", totalCount);
        model.addAttribute("pagination",
                PaginationModel.build("/line/dlq", currPage, pageSize, totalCount, Map.of()));
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

