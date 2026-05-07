package com.bangrang.workflow.app.schedule;

import com.bangrang.workflow.engine.entity.WorkflowSchedule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 스케줄 관리 — REST API + Mustache UI 페이지.
 *
 * UI:
 *  - GET    /workflow/schedules                    : 목록 페이지
 *
 * REST:
 *  - POST   /api/workflow/schedules                : 신규 등록
 *  - GET    /api/workflow/schedules                : 목록 (JSON)
 *  - GET    /api/workflow/schedules/{id}           : 단건
 *  - PUT    /api/workflow/schedules/{id}           : cron 변경
 *  - DELETE /api/workflow/schedules/{id}           : 소프트 삭제
 *  - PUT    /api/workflow/schedules/{id}/pause     : 일시중지
 *  - PUT    /api/workflow/schedules/{id}/resume    : 재개
 *  - POST   /api/workflow/schedules/{id}/run-now   : 즉시 실행
 */
@Controller
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    // ========== UI ==========

    @GetMapping("/workflow/schedules")
    public String list(Model model) {
        List<WorkflowSchedule> all = scheduleService.listAll();
        // Mustache view용 — isPaused boolean을 미리 계산 (helper 미지원 회피)
        List<java.util.Map<String, Object>> view = all.stream().map(s -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", s.id());
            m.put("definitionId", s.definitionId());
            m.put("cronExpr", s.cronExpr());
            m.put("nextRunDt", s.nextRunDt());
            m.put("lastRunDt", s.lastRunDt());
            m.put("pausedFl", s.pausedFl());
            m.put("isPaused", "Y".equals(s.pausedFl()));
            return m;
        }).toList();
        model.addAttribute("schedules", view);
        model.addAttribute("activeCount", all.stream().filter(s -> "N".equals(s.pausedFl())).count());
        model.addAttribute("pausedCount", all.stream().filter(s -> "Y".equals(s.pausedFl())).count());
        model.addAttribute("totalCount", all.size());
        // nav active
        model.addAttribute("navSchedules", true);
        return "schedules";
    }

    // ========== REST API ==========

    @ResponseBody
    @PostMapping("/api/workflow/schedules")
    public ResponseEntity<Map<String, String>> create(@RequestBody CreateRequest req) {
        String id = scheduleService.create(req.definitionId(), req.cronExpr(), req.inputData());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("scheduleId", id));
    }

    @ResponseBody
    @GetMapping("/api/workflow/schedules")
    public List<WorkflowSchedule> listJson() {
        return scheduleService.listAll();
    }

    @ResponseBody
    @GetMapping("/api/workflow/schedules/{id}")
    public WorkflowSchedule get(@PathVariable("id") String id) {
        return scheduleService.findById(id);
    }

    @ResponseBody
    @PutMapping("/api/workflow/schedules/{id}")
    public ResponseEntity<Void> updateCron(@PathVariable("id") String id,
                                            @RequestBody UpdateCronRequest req) {
        scheduleService.updateCron(id, req.cronExpr());
        return ResponseEntity.noContent().build();
    }

    @ResponseBody
    @DeleteMapping("/api/workflow/schedules/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        scheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ResponseBody
    @PutMapping("/api/workflow/schedules/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable("id") String id) {
        scheduleService.pause(id);
        return ResponseEntity.noContent().build();
    }

    @ResponseBody
    @PutMapping("/api/workflow/schedules/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable("id") String id) {
        scheduleService.resume(id);
        return ResponseEntity.noContent().build();
    }

    @ResponseBody
    @PostMapping("/api/workflow/schedules/{id}/run-now")
    public ResponseEntity<Map<String, String>> runNow(@PathVariable("id") String id) {
        String instanceId = scheduleService.runNow(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("instanceId", instanceId));
    }

    /** cron 다음 매칭 시각을 미리보기로 보여주는 헬퍼 (UI용). */
    @ResponseBody
    @GetMapping("/api/workflow/schedules/preview-cron")
    public ResponseEntity<?> previewCron(@RequestParam("cronExpr") String cronExpr) {
        try {
            CronExpression ce = CronExpression.parse(cronExpr);
            LocalDateTime base = LocalDateTime.now();
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "next1", String.valueOf(ce.next(base)),
                    "next2", String.valueOf(ce.next(ce.next(base))),
                    "next3", String.valueOf(ce.next(ce.next(ce.next(base))))
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false,
                    "message", e.getMessage()
            ));
        }
    }

    // ========== Exception 매핑 ==========

    @ResponseBody
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    // ========== DTOs ==========

    public record CreateRequest(String definitionId, String cronExpr, String inputData) {}
    public record UpdateCronRequest(String cronExpr) {}
}
