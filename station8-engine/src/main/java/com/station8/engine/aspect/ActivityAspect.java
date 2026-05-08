package com.station8.engine.aspect;

import com.station8.engine.annotation.Activity;
import com.station8.engine.core.WorkflowContext;
import com.station8.engine.util.JsonUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * @Activity 어노테이션이 붙은 메서드 호출을 가로채어 실행 이력을 기록하는 Aspect.
 * 주로 개발자가 직접 워크플로우 내부에서 Activity를 호출할 때 로그를 남기는 용도입니다.
 * (Worker에 의한 실행은 별도 로직으로 처리될 수 있음)
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

    @Around("@annotation(com.station8.engine.annotation.Activity)")
    public Object profile(ProceedingJoinPoint pjp) throws Throwable {
        // TODO: 호출 컨텍스트(WorkflowContext)를 ThreadLocal 등으로 참조할 수 있는지 검토
        // 현재는 단순한 실행 로그를 남기는 수준으로 구현
        
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

