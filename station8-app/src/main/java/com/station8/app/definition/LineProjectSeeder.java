package com.station8.app.definition;

import com.station8.engine.entity.LineProject;
import com.station8.engine.repository.LineProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * #168 — 부팅 시 default project를 시드하고, projectId가 NULL인 기존 정의를 backfill 한다.
 *
 * <h3>멱등성</h3>
 * <ul>
 *   <li>default project가 이미 있으면 insert skip</li>
 *   <li>NULL projectId 정의가 0건이면 update skip</li>
 * </ul>
 *
 * <p>운영 환경에서는 한 번 backfill 완료된 후 재기동 시 0건 update가 정상이다. 매 부팅마다
 * 한 줄 INFO 로 default project 상태를 보고한다 ({@link com.station8.app.security.InitialAdminSeeder}
 * 와 동일한 가시성 패턴).</p>
 */
@Component
public class LineProjectSeeder {

    private static final Logger log = LoggerFactory.getLogger(LineProjectSeeder.class);

    private final LineProjectRepository projectRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 컴포넌트 의존성 주입.
     *
     * @param projectRepository default project 시드 + 조회용
     * @param jdbcTemplate      기존 정의 backfill용 UPDATE 실행
     */
    public LineProjectSeeder(LineProjectRepository projectRepository, JdbcTemplate jdbcTemplate) {
        this.projectRepository = projectRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 앱 부팅 완료 후 시드 실행. 트랜잭션 안에서 (project insert + backfill UPDATE)를 atomic하게 처리.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedIfNeeded() {
        // 1. default project 멱등 시드
        LineProject existing = projectRepository.findById(LineProject.DEFAULT_PROJECT_ID);
        boolean seeded = false;
        if (existing == null) {
            projectRepository.insert(new LineProject(
                    LineProject.DEFAULT_PROJECT_ID,
                    LineProject.DEFAULT_PROJECT_NM,
                    "System default project (auto-created by LineProjectSeeder, #168)",
                    "N",
                    null, "system", null, null
            ));
            seeded = true;
        }

        // 2. NULL projectId 정의 backfill — 기존 prod DB가 PROJECT_ID 컬럼만 추가됐을 때 한 번에 채움
        int updated = jdbcTemplate.update(
                "UPDATE U_LINE_DEFINITION SET PROJECT_ID = ? WHERE PROJECT_ID IS NULL",
                LineProject.DEFAULT_PROJECT_ID);

        log.info("LineProject: defaultProject={}, status={}, backfilled={} definition(s)",
                LineProject.DEFAULT_PROJECT_NM,
                seeded ? "seeded" : "pre-existing",
                updated);
    }
}
