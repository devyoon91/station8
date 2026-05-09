package com.station8.app.schedule;

import com.station8.app.definition.LineDefinitionService;
import com.station8.engine.core.LineScheduler;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.entity.LineSchedule;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.repository.LineScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 스케줄 관리 서비스 — UI/REST가 호출하는 진입점.
 * - 등록 시 cron 표현식 검증 + 첫 NEXT_RUN_DT 자동 계산
 * - 일시중지/재개/즉시실행/cron 변경/삭제
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final LineScheduleRepository scheduleRepository;
    private final LineDefinitionRepository definitionRepository;
    private final LineDefinitionService definitionService;

    public ScheduleService(LineScheduleRepository scheduleRepository,
                           LineDefinitionRepository definitionRepository,
                           LineDefinitionService definitionService) {
        this.scheduleRepository = scheduleRepository;
        this.definitionRepository = definitionRepository;
        this.definitionService = definitionService;
    }

    /** 신규 등록. cron 표현식이 잘못되면 IllegalArgumentException. */
    @Transactional
    public String create(String definitionId, String cronExpr, String inputData) {
        LineDefinition def = definitionRepository.findDefinitionById(definitionId);
        if (def == null || "Y".equals(def.delFl())) {
            throw new IllegalArgumentException("정의를 찾을 수 없습니다: " + definitionId);
        }
        if (cronExpr == null || cronExpr.isBlank()) {
            throw new IllegalArgumentException("cronExpr 필수");
        }
        try {
            CronExpression.parse(cronExpr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("유효하지 않은 cron 표현식: " + cronExpr, e);
        }

        String id = UUID.randomUUID().toString();
        LocalDateTime nextRun = LineScheduler.nextFromCron(cronExpr, LocalDateTime.now());
        LineSchedule s = new LineSchedule(
                id, definitionId, cronExpr, nextRun, null,
                "N", inputData, "Y", "Y", "N", null, "api", null, null
        );
        scheduleRepository.insert(s);
        log.info("스케줄 등록: id={}, definitionId={}, cron={}, nextRun={}", id, definitionId, cronExpr, nextRun);
        return id;
    }

    @Transactional(readOnly = true)
    public List<LineSchedule> listAll() {
        return scheduleRepository.findAll();
    }

    /** 페이지 조회 (#97). */
    @Transactional(readOnly = true)
    public List<LineSchedule> listPage(int offset, int limit) {
        return scheduleRepository.findPage(offset, limit);
    }

    /** 살아있는 스케줄 총 행 수 (#97). */
    @Transactional(readOnly = true)
    public long count() {
        return scheduleRepository.count();
    }

    /** PAUSED_FL별 카운트 — 헤더 통계용 (#97). */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> countByPaused() {
        return scheduleRepository.countByPaused();
    }

    @Transactional(readOnly = true)
    public LineSchedule findById(String id) {
        LineSchedule s = scheduleRepository.findById(id);
        if (s == null) throw new IllegalArgumentException("스케줄을 찾을 수 없습니다: " + id);
        return s;
    }

    @Transactional
    public void pause(String id) {
        ensureExists(id);
        scheduleRepository.setPaused(id, true);
    }

    @Transactional
    public void resume(String id) {
        ensureExists(id);
        scheduleRepository.setPaused(id, false);
    }

    @Transactional
    public void updateCron(String id, String cronExpr) {
        ensureExists(id);
        try {
            CronExpression.parse(cronExpr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("유효하지 않은 cron 표현식: " + cronExpr, e);
        }
        LocalDateTime nextRun = LineScheduler.nextFromCron(cronExpr, LocalDateTime.now());
        scheduleRepository.updateCron(id, cronExpr, nextRun);
    }

    @Transactional
    public void delete(String id) {
        // 멱등 삭제 (이미 없거나 삭제된 경우 무시)
        scheduleRepository.softDelete(id);
    }

    /**
     * cron과 무관하게 즉시 실행. 정의는 스케줄의 ``DEFINITION_ID``를 사용.
     * @return 생성된 instanceId
     */
    @Transactional
    public String runNow(String id) {
        LineSchedule s = ensureExists(id);
        return definitionService.runDefinition(s.definitionId(), s.inputData());
    }

    private LineSchedule ensureExists(String id) {
        LineSchedule s = scheduleRepository.findById(id);
        if (s == null) throw new IllegalArgumentException("스케줄을 찾을 수 없습니다: " + id);
        return s;
    }
}
