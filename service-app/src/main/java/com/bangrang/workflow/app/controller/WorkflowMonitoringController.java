package com.bangrang.workflow.app.controller;

import com.bangrang.workflow.engine.core.WorkflowExecutor;
import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.entity.DlqEntry;
import com.bangrang.workflow.engine.entity.WorkflowInstance;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.repository.DlqRepository;
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
@RequestMapping("/workflow")
public class WorkflowMonitoringController {

    private final ActivityRepository activityRepository;
    private final WorkflowExecutor workflowExecutor;
    private final DlqRepository dlqRepository;

    public WorkflowMonitoringController(ActivityRepository activityRepository, 
                                        WorkflowExecutor workflowExecutor,
                                        DlqRepository dlqRepository) {
        this.activityRepository = activityRepository;
        this.workflowExecutor = workflowExecutor;
        this.dlqRepository = dlqRepository;
    }

    /**
     * 전체 워크플로우 인스턴스 목록 대시보드
     */
    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(value = "workflowName", required = false) String workflowName,
                            @RequestParam(value = "statusSt", required = false) String statusSt,
                            @RequestParam(value = "instanceId", required = false) String instanceId,
                            Model model) {
        List<WorkflowInstance> instances = activityRepository.findAllInstances();
        
        // 필터링 적용 (메모리 필터링 - 소규모 앱용)
        List<WorkflowInstance> filtered = instances.stream()
                .filter(i -> workflowName == null || workflowName.isEmpty() || i.workflowName().contains(workflowName))
                .filter(i -> statusSt == null || statusSt.isEmpty() || i.statusSt().equals(statusSt))
                .filter(i -> instanceId == null || instanceId.isEmpty() || i.id().contains(instanceId))
                .collect(Collectors.toList());

        model.addAttribute("instances", filtered);
        
        // 검색 필드 유지를 위해 다시 모델에 추가
        model.addAttribute("workflowName", workflowName);
        model.addAttribute("statusSt", statusSt);
        model.addAttribute("instanceId", instanceId);
        
        // 전체 통계 계산 (필터링 전 데이터 기준)
        long runningCount = instances.stream().filter(i -> "RUNNING".equals(i.statusSt())).count();
        long completedCount = instances.stream().filter(i -> "COMPLETED".equals(i.statusSt())).count();
        long failedCount = instances.stream().filter(i -> "FAILED".equals(i.statusSt())).count();
        
        model.addAttribute("runningCount", runningCount);
        model.addAttribute("completedCount", completedCount);
        model.addAttribute("failedCount", failedCount);
        model.addAttribute("totalCount", instances.size());
        
        return "dashboard";
    }

    /**
     * 특정 인스턴스의 상세 실행 타임라인
     */
    @GetMapping("/instance/{id}")
    public String timeline(@PathVariable("id") String instanceId, Model model) {
        WorkflowInstance instance = activityRepository.findInstanceById(instanceId);
        List<ActivityExecution> activities = activityRepository.findActivitiesByInstanceId(instanceId);
        
        model.addAttribute("instance", instance);
        model.addAttribute("activities", activities);
        
        return "timeline";
    }

    /**
     * 실패한 워크플로우 재개
     */
    @PostMapping("/instance/{id}/resume")
    public String resume(@PathVariable("id") String instanceId) {
        workflowExecutor.resumeWorkflow(instanceId);
        return "redirect:/workflow/instance/" + instanceId;
    }

    /**
     * DLQ 목록 조회
     */
    @GetMapping("/dlq")
    public String dlqList(Model model) {
        List<DlqEntry> dlqEntries = dlqRepository.findAll();
        model.addAttribute("dlqEntries", dlqEntries);
        long newCount = dlqEntries.stream().filter(e -> "NEW".equals(e.dlqStatusSt())).count();
        long requeuedCount = dlqEntries.stream().filter(e -> "REQUEUED".equals(e.dlqStatusSt())).count();
        long discardedCount = dlqEntries.stream().filter(e -> "DISCARDED".equals(e.dlqStatusSt())).count();
        model.addAttribute("newCount", newCount);
        model.addAttribute("requeuedCount", requeuedCount);
        model.addAttribute("discardedCount", discardedCount);
        model.addAttribute("totalDlqCount", dlqEntries.size());
        return "dlq";
    }

    /**
     * DLQ 항목 상세 조회
     */
    @GetMapping("/dlq/{id}")
    public String dlqDetail(@PathVariable("id") String dlqId, Model model) {
        DlqEntry entry = dlqRepository.findById(dlqId);
        model.addAttribute("entry", entry);
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
        return "redirect:/workflow/dlq";
    }

    /**
     * DLQ 항목 폐기 (Discard): DLQ 레코드 상태만 업데이트
     */
    @PostMapping("/dlq/{id}/discard")
    public String dlqDiscard(@PathVariable("id") String dlqId) {
        dlqRepository.updateStatus(dlqId, "DISCARDED");
        return "redirect:/workflow/dlq";
    }
}

