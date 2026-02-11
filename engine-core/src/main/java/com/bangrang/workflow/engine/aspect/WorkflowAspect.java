package com.bangrang.workflow.engine.aspect;

import com.bangrang.workflow.engine.annotation.Workflow;
import com.bangrang.workflow.engine.util.JsonUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
public class WorkflowAspect {
    private static final Logger log = LoggerFactory.getLogger(WorkflowAspect.class);
    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;

    public WorkflowAspect(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    @Around("@within(com.bangrang.workflow.engine.annotation.Workflow) || @annotation(com.bangrang.workflow.engine.annotation.Workflow)")
    public Object profile(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Workflow workflow = pjp.getTarget().getClass().getAnnotation(Workflow.class);
        
        String workflowName = (workflow != null && !workflow.value().isEmpty()) 
            ? workflow.value() : pjp.getTarget().getClass().getSimpleName();
            
        String instanceId = UUID.randomUUID().toString();
        Object[] args = pjp.getArgs();
        String inputJson = (args.length > 0) ? jsonUtil.toJson(args[0]) : null;

        // 1. 워크플로우 시작 로그 (U_WF_INSTANCE 기록)
        // TODO: 중단된 워크플로우 재개(resume) 시나리오 대응을 위한 이미 존재하는 인스턴스 체크 로직 필요
        log.info("Starting workflow: {} (ID: {})", workflowName, instanceId);
        jdbcTemplate.update("""
            INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId, workflowName, inputJson);

        try {
            Object result = pjp.proceed();
            
            // 2. 워크플로우 성공 종료 로그
            String outputJson = jsonUtil.toJson(result);
            jdbcTemplate.update("""
                UPDATE U_WF_INSTANCE 
                SET STATUS_ST = 'COMPLETED', OUTPUT_DATA = ?, END_DT = CURRENT_TIMESTAMP, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, outputJson, instanceId);
            
            return result;
        } catch (Exception e) {
            // 3. 워크플로우 실패 로그
            // TODO: 실패 시 상세 에러 메시지 및 스택트레이스 저장 필드(U_WF_INSTANCE에 추가 필요) 기록 로직 보완
            jdbcTemplate.update("""
                UPDATE U_WF_INSTANCE 
                SET STATUS_ST = 'FAILED', END_DT = CURRENT_TIMESTAMP, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, instanceId);
            throw e;
        }
    }
}

