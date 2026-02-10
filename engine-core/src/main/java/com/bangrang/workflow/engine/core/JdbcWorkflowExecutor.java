package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * WorkflowExecutor ?명꽣?섏씠?ㅼ쓽 ?ㅺ뎄?꾩껜.
 */
@Service
public class JdbcWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowExecutor.class);

    private final JdbcTemplate jdbcTemplate;
    private final ActivityRepository activityRepository;
    private final JsonUtil jsonUtil;

    public JdbcWorkflowExecutor(JdbcTemplate jdbcTemplate, 
                                ActivityRepository activityRepository, 
                                JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.activityRepository = activityRepository;
        this.jsonUtil = jsonUtil;
    }

    @Override
    @Transactional
    public String startWorkflow(String workflowName, Object input) {
        String instanceId = UUID.randomUUID().toString();
        String inputJson = jsonUtil.toJson(input);

        log.info("Starting workflow: {} (Instance ID: {})", workflowName, instanceId);

        // U_WF_INSTANCE 湲곕줉 (Aspect?먯꽌??湲곕줉?섏?留? 紐낆떆???몄텧 ???
        jdbcTemplate.update("""
            INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId, workflowName, inputJson);

        // 泥?踰덉㎏ ?≫떚鍮꾪떚瑜?李얜뒗 濡쒖쭅???꾩슂?????덉쑝?? 
        // ?꾩옱 ?붿쭊? 媛쒕컻?먭? Workflow 濡쒖쭅 ?댁뿉??泥?Activity瑜??몄텧?섍굅??
        // ?몃??먯꽌 泥?Activity PENDING???ｌ뼱二쇰뒗 諛⑹떇?쇰줈 ?숈옉??
        
        return instanceId;
    }

    @Override
    @Transactional
    public void resumeWorkflow(String instanceId) {
        log.info("Resuming workflow instance: {}", instanceId);

        // 1. ?몄뒪?댁뒪 ?곹깭瑜?RUNNING?쇰줈 蹂듦뎄 (FAILED??寃쎌슦 ?鍮?
        jdbcTemplate.update("""
            UPDATE U_WF_INSTANCE 
            SET STATUS_ST = 'RUNNING', EDIT_DT = CURRENT_TIMESTAMP 
            WHERE ID = ?
            """, instanceId);

        // 2. FAILED ?곹깭?닿굅??以묐떒??PENDING?대㈃??NEXT_RETRY_DT媛 癒?誘몃옒???? ?쒕룞??李얠븘 PENDING?쇰줈 蹂듦뎄
        List<ActivityExecution> activities = activityRepository.findActivitiesByInstanceId(instanceId);
        
        boolean resumed = false;
        for (ActivityExecution activity : activities) {
            if ("FAILED".equals(activity.statusSt())) {
                log.info("Reseting failed activity to PENDING: {} (ID: {})", activity.activityName(), activity.id());
                activityRepository.resetToPending(activity.id());
                resumed = true;
                // ??踰덉뿉 ?섎굹???ш컻?섍굅???꾩껜瑜??ш컻?????덉쑝?? ?ш린?쒕뒗 FAILED??紐⑤뱺 寃껋쓣 ?ш컻 ??곸쑝濡???
            }
        }

        if (!resumed) {
            log.warn("No failed activities found to resume for instance: {}", instanceId);
        }
    }
}

