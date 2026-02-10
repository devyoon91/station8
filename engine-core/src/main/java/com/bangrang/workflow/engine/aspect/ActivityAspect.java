package com.bangrang.workflow.engine.aspect;

import com.bangrang.workflow.engine.annotation.Activity;
import com.bangrang.workflow.engine.core.WorkflowContext;
import com.bangrang.workflow.engine.util.JsonUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * @Activity ?대끂?뚯씠?섏씠 遺숈? 硫붿꽌???몄텧??媛濡쒖콈???ㅽ뻾 ?대젰??湲곕줉?섎뒗 Aspect.
 * 二쇰줈 媛쒕컻?먭? 吏곸젒 ?뚰겕?뚮줈???대??먯꽌 Activity瑜??몄텧????濡쒓렇瑜??④린???⑸룄?낅땲??
 * (Worker???섑븳 ?ㅽ뻾? 蹂꾨룄 濡쒖쭅?쇰줈 泥섎━?????덉쓬)
 */
@Aspect
@Component
public class ActivityAspect {
    private static final Logger log = LoggerFactory.getLogger(ActivityAspect.class);
    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;

    public ActivityAspect(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    @Around("@annotation(com.bangrang.workflow.engine.annotation.Activity)")
    public Object profile(ProceedingJoinPoint pjp) throws Throwable {
        // TODO: ?몄텧 而⑦뀓?ㅽ듃(WorkflowContext)瑜?ThreadLocal ?깆쑝濡?李몄“?????덈뒗吏 寃??
        // ?꾩옱???⑥닚???ㅽ뻾 濡쒓렇瑜??④린???섏??쇰줈 援ы쁽
        
        String methodName = pjp.getSignature().getName();
        Object[] args = pjp.getArgs();
        
        log.debug("Activity aspect triggered for method: {}", methodName);

        try {
            Object result = pjp.proceed();
            return result;
        } catch (Throwable e) {
            log.error("Activity execution failed in aspect: " + methodName, e);
            throw e;
        }
    }
}

