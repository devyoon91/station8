package com.station8.app.controller;

import com.station8.engine.core.LineExecutor;
import com.station8.engine.entity.ActivityExecution;
import com.station8.engine.entity.LineInstance;
import com.station8.engine.repository.ActivityRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 라인 인스턴스 운영 REST API.
 *
 * <ul>
 *   <li>POST /api/line/instances/{id}/terminate — 강제 종료 (#101)</li>
 *   <li>GET  /api/line/instances/{id}/state — 인스턴스 + 활동 + 노선도 상태 스냅샷 (#132 timeline live update)</li>
 * </ul>
 *
 * <p>UI 액션({@code POST /line/instance/{id}/terminate})과 별개 — 자동화/스크립트용 + timeline AJAX 폴링용.</p>
 */
@RestController
@RequestMapping("/api/line/instances")
public class LineInstanceController {

    private final LineExecutor lineExecutor;
    private final ActivityRepository activityRepository;

    public LineInstanceController(LineExecutor lineExecutor,
                                  ActivityRepository activityRepository) {
        this.lineExecutor = lineExecutor;
        this.activityRepository = activityRepository;
    }

    @PostMapping("/{id}/terminate")
    public ResponseEntity<Map<String, String>> terminate(@PathVariable("id") String instanceId) {
        try {
            lineExecutor.terminateLine(instanceId);
            return ResponseEntity.ok(Map.of("status", "TERMINATED", "instanceId", instanceId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", ex.getMessage()));
        }
    }

    /**
     * #132 — timeline 페이지 AJAX 폴링용 상태 스냅샷.
     *
     * <p>{@link LineMonitoringController#timeline}의 derived 필드와 같은 모양으로 만들어
     * UI가 인스턴스 status / 활동 카드 / 노선도 statusByNode를 부분 갱신할 수 있게 한다.</p>
     */
    @GetMapping("/{id}/state")
    public ResponseEntity<InstanceStateDto> state(@PathVariable("id") String instanceId) {
        LineInstance instance;
        try {
            instance = activityRepository.findInstanceById(instanceId);
        } catch (EmptyResultDataAccessException ex) {
            return ResponseEntity.notFound().build();
        }
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }

        List<ActivityExecution> activities = activityRepository.findActivitiesByInstanceId(instanceId);
        String statusSt = instance.statusSt();
        boolean terminal = "COMPLETED".equals(statusSt) || "FAILED".equals(statusSt)
                || "TERMINATED".equals(statusSt);

        InstanceSummary inst = new InstanceSummary(
                instance.id(), statusSt,
                /*running*/ "RUNNING".equals(statusSt),
                /*failed*/ "FAILED".equals(statusSt),
                /*terminated*/ "TERMINATED".equals(statusSt),
                /*terminal*/ terminal,
                InstanceStateBuilder.badgeForInstanceStatus(statusSt),
                instance.endDt());

        List<ActivitySummary> actSummaries = activities.stream()
                .map(a -> new ActivitySummary(
                        a.id(),
                        a.nodeId(),
                        a.activityName(),
                        a.statusSt(),
                        InstanceStateBuilder.badgeForInstanceStatus(a.statusSt()),
                        InstanceStateBuilder.dotForActivityStatus(a.statusSt()),
                        a.startDt(),
                        a.endDt(),
                        a.inputData(),
                        a.outputData(),
                        a.errorMessage(),
                        a.retryCnt()))
                .toList();

        Map<String, String> statusByNode = InstanceStateBuilder.buildStatusByNode(activities);

        return ResponseEntity.ok(new InstanceStateDto(inst, actSummaries, statusByNode));
    }

    /** state JSON 응답 최상위 — instance + activities + 노선도 상태 매핑. */
    public record InstanceStateDto(
            InstanceSummary instance,
            List<ActivitySummary> activities,
            Map<String, String> statusByNode
    ) {}

    /**
     * 인스턴스 요약 — UI에서 status badge / 토글 표시 결정에 필요한 derived flag 포함.
     *
     * <p>boolean 필드는 record component getter가 \`xxx()\`이라 Jackson이 property name을
     * record 필드 그대로 직렬화한다 — \`is\` 접두어 없는 이름으로 둠 (JS에서 \`data.instance.running\` 등).</p>
     */
    public record InstanceSummary(
            String id,
            String statusSt,
            boolean running,
            boolean failed,
            boolean terminated,
            boolean terminal,
            String badgeClass,
            LocalDateTime endDt
    ) {}

    /** 활동 한 건. timeline 카드 갱신에 필요한 모든 필드. */
    public record ActivitySummary(
            String id,
            String nodeId,
            String activityName,
            String statusSt,
            String badgeClass,
            String dotClass,
            LocalDateTime startDt,
            LocalDateTime endDt,
            String inputData,
            String outputData,
            String errorMessage,
            int retryCnt
    ) {}
}
