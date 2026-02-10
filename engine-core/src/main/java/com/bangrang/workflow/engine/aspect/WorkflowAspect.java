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

        // 1. ?뚰겕?뚮줈???쒖옉 濡쒓렇 (U_WF_INSTANCE 湲곕줉)
        // TODO: 以묐떒???뚰겕?뚮줈???ш컻(resume) ?쒕굹由ъ삤 ??묒쓣 ?꾪븳 ?대? 議댁옱?섎뒗 ?몄뒪?댁뒪 泥댄겕 濡쒖쭅 ?꾩슂
        log.info("Starting workflow: {} (ID: {})", workflowName, instanceId);
        jdbcTemplate.update("""
            INSERT INTO U_WF_INSTANCE (ID, WORKFLOW_NAME, STATUS_ST, INPUT_DATA, USE_FL, VIEW_FL, DEL_FL, START_DT, REG_DT)
            VALUES (?, ?, 'RUNNING', ?, 'Y', 'Y', 'N', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, instanceId, workflowName, inputJson);

        try {
            Object result = pjp.proceed();
            
            // 2. ?뚰겕?뚮줈???깃났 醫낅즺 濡쒓렇
            String outputJson = jsonUtil.toJson(result);
            jdbcTemplate.update("""
                UPDATE U_WF_INSTANCE 
                SET STATUS_ST = 'COMPLETED', OUTPUT_DATA = ?, END_DT = CURRENT_TIMESTAMP, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, outputJson, instanceId);
            
            return result;
        } catch (Exception e) {
            // 3. ?뚰겕?뚮줈???ㅽ뙣 濡쒓렇
            // TODO: ?ㅽ뙣 ???곸꽭 ?먮윭 硫붿떆吏 諛??ㅽ깮?몃젅?댁뒪 ????꾨뱶(U_WF_INSTANCE??異붽? ?꾩슂) 湲곕줉 濡쒖쭅 蹂댁셿
            jdbcTemplate.update("""
                UPDATE U_WF_INSTANCE 
                SET STATUS_ST = 'FAILED', END_DT = CURRENT_TIMESTAMP, EDIT_DT = CURRENT_TIMESTAMP
                WHERE ID = ?
                """, instanceId);
            throw e;
        }
    }
}

