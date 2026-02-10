package com.bangrang.workflow.app.controller;

import com.bangrang.workflow.engine.core.WorkflowExecutor;
import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.entity.WorkflowInstance;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/workflow")
public class WorkflowMonitoringController {

    private final ActivityRepository activityRepository;
    private final WorkflowExecutor workflowExecutor;

    public WorkflowMonitoringController(ActivityRepository activityRepository, 
                                        WorkflowExecutor workflowExecutor) {
        this.activityRepository = activityRepository;
        this.workflowExecutor = workflowExecutor;
    }

    /**
     * 전체 워크플로우 인스턴스 목록 대시보드
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        List<WorkflowInstance> instances = activityRepository.findAllInstances();
        model.addAttribute("instances", instances);
        
        // 간단한 통계 계산
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
}

