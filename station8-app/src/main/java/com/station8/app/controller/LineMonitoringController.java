package com.station8.app.controller;

import com.station8.engine.core.LineExecutor;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.DlqEntry;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import com.station8.engine.repository.DlqRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/line")
public class LineMonitoringController {

    private final ActivityRepository activityRepository;
    private final LineExecutor workflowExecutor;
    private final DlqRepository dlqRepository;

    public LineMonitoringController(ActivityRepository activityRepository, 
                                        LineExecutor workflowExecutor,
                                        DlqRepository dlqRepository) {
        this.activityRepository = activityRepository;
        this.workflowExecutor = workflowExecutor;
        this.dlqRepository = dlqRepository;
    }

    /**
     * 전체 라인 인스턴스 목록 대시보드
     */
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(value = "workflowName", required = false) String workflowName,
                            @RequestParam(value = "statusSt", required = false) String statusSt,
                            @RequestParam(value = "instanceId", required = false) String instanceId,
                            Model model) {
        List<LineInstance> instances = activityRepository.findAllInstances();
        
        // 필터링 적용 (메모리 필터링 - 소규모 앱용)
        List<LineInstance> filtered = instances.stream()
                .filter(i -> workflowName == null || workflowName.isEmpty() || i.workflowName().contains(workflowName))
                .filter(i -> statusSt == null || statusSt.isEmpty() || i.statusSt().equals(statusSt))
                .filter(i -> instanceId == null || instanceId.isEmpty() || i.id().contains(instanceId))
                .collect(Collectors.toList());

        // Mustache는 helper 미지원 — instance마다 배지 색상 클래스를 미리 계산해 view에 전달
        List<java.util.Map<String, Object>> instanceViews = filtered.stream().map(i -> {
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
        
        // 검색 필드 유지를 위해 다시 모델에 추가
        model.addAttribute("workflowName", workflowName);
        model.addAttribute("statusSt", statusSt);
        model.addAttribute("instanceId", instanceId);
        // Mustache(JMustache)는 ``{{#equals}}`` helper 미지원 — selected 상태를 미리 boolean으로 계산
        model.addAttribute("selectedRunning", "RUNNING".equals(statusSt));
        model.addAttribute("selectedCompleted", "COMPLETED".equals(statusSt));
        model.addAttribute("selectedFailed", "FAILED".equals(statusSt));
        model.addAttribute("selectedTerminated", "TERMINATED".equals(statusSt));
        
        // 전체 통계 계산 (필터링 전 데이터 기준)
        long runningCount = instances.stream().filter(i -> "RUNNING".equals(i.statusSt())).count();
        long completedCount = instances.stream().filter(i -> "COMPLETED".equals(i.statusSt())).count();
        long failedCount = instances.stream().filter(i -> "FAILED".equals(i.statusSt())).count();
        
        model.addAttribute("runningCount", runningCount);
        model.addAttribute("completedCount", completedCount);
        model.addAttribute("failedCount", failedCount);
        model.addAttribute("totalCount", instances.size());
        model.addAttribute("navDashboard", true);

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
        model.addAttribute("instance", instView);

        List<java.util.Map<String, Object>> actViews = activities.stream().map(a -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
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

        return "timeline";
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
     * DLQ 목록 조회
     */
    @GetMapping("/dlq")
    public String dlqList(Model model) {
        List<DlqEntry> dlqEntries = dlqRepository.findAll();
        // view-derived 필드를 미리 계산
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
        long newCount = dlqEntries.stream().filter(e -> "NEW".equals(e.dlqStatusSt())).count();
        long requeuedCount = dlqEntries.stream().filter(e -> "REQUEUED".equals(e.dlqStatusSt())).count();
        long discardedCount = dlqEntries.stream().filter(e -> "DISCARDED".equals(e.dlqStatusSt())).count();
        model.addAttribute("newCount", newCount);
        model.addAttribute("requeuedCount", requeuedCount);
        model.addAttribute("discardedCount", discardedCount);
        model.addAttribute("totalDlqCount", dlqEntries.size());
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

