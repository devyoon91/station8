package com.station8.app.schedule;

import com.station8.app.util.Dates;
import com.station8.app.util.PaginationModel;
import com.station8.engine.entity.LineSchedule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *  - GET    /line/schedules                    : 목록 페이지
 *
 * REST:
 *  - POST   /api/line/schedules                : 신규 등록
 *  - GET    /api/line/schedules                : 목록 (JSON)
 *  - GET    /api/line/schedules/{id}           : 단건
 *  - PUT    /api/line/schedules/{id}           : cron 변경
 *  - DELETE /api/line/schedules/{id}           : 소프트 삭제
 *  - PUT    /api/line/schedules/{id}/pause     : 일시중지
 *  - PUT    /api/line/schedules/{id}/resume    : 재개
 *  - POST   /api/line/schedules/{id}/run-now   : 즉시 실행
 */
@Controller
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final com.station8.app.security.LineAclService aclService;
    private final com.station8.engine.repository.LineDefinitionRepository definitionRepository;

    public ScheduleController(ScheduleService scheduleService,
                              com.station8.app.security.LineAclService aclService,
                              com.station8.engine.repository.LineDefinitionRepository definitionRepository) {
        this.scheduleService = scheduleService;
        this.aclService = aclService;
        this.definitionRepository = definitionRepository;
    }

    // ========== UI ==========

    @GetMapping("/line/schedules")
    public String list(@RequestParam(value = "page", required = false) Integer page,
                       @RequestParam(value = "size", required = false) Integer size,
                       Model model) {
        int pageSize = PaginationModel.normalizeSize(size);

        // #159 — ACL READ 가시성: ADMIN이 아닐 경우 가시 정의 ID 집합으로 in-app 필터.
        // 스케줄 행 수는 보통 작아서 listAll() 후 인메모리 슬라이싱으로 충분.
        List<com.station8.engine.entity.LineDefinition> active =
                definitionRepository.findActiveDefinitionsPage(0, 10000);
        java.util.Set<String> visibleIds = aclService.visibleDefinitionIds(
                active.stream().map(com.station8.engine.entity.LineDefinition::id).toList());

        List<LineSchedule> all = (visibleIds == null)
                ? scheduleService.listAll()
                : scheduleService.listAll().stream()
                        .filter(s -> visibleIds.contains(s.definitionId()))
                        .toList();

        long totalCount = all.size();
        int totalPages = (totalCount <= 0) ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        int currPage = PaginationModel.normalizePage(page, totalPages);
        int offset = currPage * pageSize;
        int end = (int) Math.min((long) offset + pageSize, totalCount);
        List<LineSchedule> rows = (offset >= totalCount) ? List.of() : all.subList(offset, end);

        List<java.util.Map<String, Object>> view = rows.stream().map(s -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", s.id());
            m.put("definitionId", s.definitionId());
            m.put("cronExpr", s.cronExpr());
            m.put("nextRunDt", Dates.format(s.nextRunDt()));
            m.put("lastRunDt", Dates.format(s.lastRunDt()));
            m.put("pausedFl", s.pausedFl());
            m.put("isPaused", "Y".equals(s.pausedFl()));
            return m;
        }).toList();
        model.addAttribute("schedules", view);

        // 헤더 통계 — 가시 스케줄 기준 카운트 (active/paused 필터링)
        long activeCount = all.stream().filter(s -> !"Y".equals(s.pausedFl())).count();
        long pausedCount = all.stream().filter(s -> "Y".equals(s.pausedFl())).count();
        model.addAttribute("activeCount", activeCount);
        model.addAttribute("pausedCount", pausedCount);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("pagination",
                PaginationModel.build("/line/schedules", currPage, pageSize, totalCount, Map.of()));
        model.addAttribute("navSchedules", true);
        return "schedules";
    }

    // ========== REST API ==========

    /**
     * 신규 스케줄 등록. SCHEDULE 권한 필요.
     *
     * @param req 등록 요청 본문 — Bean Validation으로 1차 검증 (#175). 검증 실패는
     *            {@code GlobalRestExceptionHandler}가 400 + {@code VALIDATION_FAILED}로 변환.
     * @return 201 + {@code {scheduleId}}
     */
    @ResponseBody
    @PostMapping("/api/line/schedules")
    @PreAuthorize("@lineAcl.canSchedule(#req.definitionId())")
    public ResponseEntity<Map<String, String>> create(@jakarta.validation.Valid @RequestBody CreateRequest req) {
        String id = scheduleService.create(req.definitionId(), req.cronExpr(), req.inputData());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("scheduleId", id));
    }

    @ResponseBody
    @GetMapping("/api/line/schedules")
    public List<LineSchedule> listJson() {
        return scheduleService.listAll();
    }

    @ResponseBody
    @GetMapping("/api/line/schedules/{id}")
    public LineSchedule get(@PathVariable("id") String id) {
        return scheduleService.findById(id);
    }

    /**
     * cron 표현식 변경. SCHEDULE 권한 필요.
     *
     * @param id  대상 스케줄 ID
     * @param req cron 변경 요청 — Bean Validation으로 cronExpr 필수 검증
     * @return 204 No Content
     */
    @ResponseBody
    @PutMapping("/api/line/schedules/{id}")
    @PreAuthorize("@lineAcl.canScheduleByScheduleId(#id)")
    public ResponseEntity<Void> updateCron(@PathVariable("id") String id,
                                            @jakarta.validation.Valid @RequestBody UpdateCronRequest req) {
        scheduleService.updateCron(id, req.cronExpr());
        return ResponseEntity.noContent().build();
    }

    @ResponseBody
    @DeleteMapping("/api/line/schedules/{id}")
    @PreAuthorize("@lineAcl.canScheduleByScheduleId(#id)")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        scheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ResponseBody
    @PutMapping("/api/line/schedules/{id}/pause")
    @PreAuthorize("@lineAcl.canScheduleByScheduleId(#id)")
    public ResponseEntity<Void> pause(@PathVariable("id") String id) {
        scheduleService.pause(id);
        return ResponseEntity.noContent().build();
    }

    @ResponseBody
    @PutMapping("/api/line/schedules/{id}/resume")
    @PreAuthorize("@lineAcl.canScheduleByScheduleId(#id)")
    public ResponseEntity<Void> resume(@PathVariable("id") String id) {
        scheduleService.resume(id);
        return ResponseEntity.noContent().build();
    }

    /** #140 — run-now는 EXECUTE 권한 (실행 행위라). 스케줄은 트리거 매개체일 뿐. */
    @ResponseBody
    @PostMapping("/api/line/schedules/{id}/run-now")
    @PreAuthorize("@lineAcl.canScheduleByScheduleId(#id)")
    public ResponseEntity<Map<String, String>> runNow(@PathVariable("id") String id) {
        String instanceId = scheduleService.runNow(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("instanceId", instanceId));
    }

    /** cron 다음 매칭 시각을 미리보기로 보여주는 헬퍼 (UI용). */
    @ResponseBody
    @GetMapping("/api/line/schedules/preview-cron")
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

    // 예외 매핑은 #174 GlobalRestExceptionHandler로 통합 — controller-level 핸들러 제거.

    // ========== DTOs ==========

    /**
     * 스케줄 등록 요청.
     *
     * @param definitionId 대상 라인 정의 ID. 필수.
     * @param cronExpr     cron 표현식 (Spring CronExpression 형식). 필수.
     * @param inputData    인스턴스 시작 시 전달할 입력 (선택, JSON/String).
     */
    public record CreateRequest(
            @jakarta.validation.constraints.NotBlank(message = "definitionId는 필수입니다.")
            String definitionId,
            @jakarta.validation.constraints.NotBlank(message = "cronExpr은 필수입니다.")
            String cronExpr,
            String inputData
    ) {}

    /**
     * cron 변경 요청.
     *
     * @param cronExpr 새 cron 표현식. 필수.
     */
    public record UpdateCronRequest(
            @jakarta.validation.constraints.NotBlank(message = "cronExpr은 필수입니다.")
            String cronExpr
    ) {}
}
