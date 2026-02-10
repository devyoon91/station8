package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DB?먯꽌 ?ㅽ뻾 ?湲?以묒씤 ?묒뾽??媛?몄? ?ㅻ젅????먯꽌 ?ㅽ뻾?섎뒗 ?뚯빱 ?대옒??
 * Spring??@Scheduled瑜??댁슜??二쇨린?곸쑝濡??대쭅?섎ŉ, ThreadPoolTaskExecutor瑜??듯빐 ?숈떆?깆쓣 ?쒖뼱?⑸땲??
 */
@Component
public class WorkflowWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

    private final ActivityRepository activityRepository;
    private final TaskExecutor taskExecutor;
    private final ThreadPoolTaskExecutor workflowTaskExecutor;
    private final WorkflowRegistry workflowRegistry;
    private final ExponentialBackoffRetryPolicy retryPolicy;

    public WorkflowWorker(ActivityRepository activityRepository, 
                          TaskExecutor taskExecutor,
                          ThreadPoolTaskExecutor workflowTaskExecutor,
                          WorkflowRegistry workflowRegistry,
                          ExponentialBackoffRetryPolicy retryPolicy) {
        this.activityRepository = activityRepository;
        this.taskExecutor = taskExecutor;
        this.workflowTaskExecutor = workflowTaskExecutor;
        this.workflowRegistry = workflowRegistry;
        this.retryPolicy = retryPolicy;
    }

    /**
     * 二쇨린?곸쑝濡?DB瑜??대쭅?섏뿬 泥섎━ 媛?ν븳 ?묒뾽??媛?몄샃?덈떎.
     * (?? 1珥덈쭏???ㅽ뻾)
     */
    @Scheduled(fixedDelayString = "${workflow.polling.interval-ms:1000}")
    public void pollActivities() {
        log.trace("Polling pending activities...");
        
        // ??踰덉쓽 ?대쭅?먯꽌 媛?몄삱 理쒕? ?묒뾽 ??
        int limit = 10; 
        List<ActivityExecution> pendingActivities = activityRepository.findPendingActivitiesWithLock(limit);

        for (ActivityExecution activity : pendingActivities) {
            log.info("Dispatching activity: {} (Instance ID: {})", activity.activityName(), activity.instanceId());
            
            // 蹂꾨룄???ㅻ젅????먯꽌 鍮꾨룞湲??ㅽ뻾
            workflowTaskExecutor.execute(() -> processActivity(activity));
        }
    }

    /**
     * 媛쒕퀎 ?≫떚鍮꾪떚瑜??ㅽ뻾?섍퀬 寃곌낵瑜??낅뜲?댄듃?섎뒗 ?ㅼ젣 濡쒖쭅.
     */
    private void processActivity(ActivityExecution activity) {
        // 1. ?덉??ㅽ듃由ъ뿉???≫떚鍮꾪떚 硫뷀??곗씠??議고쉶
        WorkflowRegistry.ActivityMetadata metadata = workflowRegistry.getActivity(activity.activityName());
        if (metadata == null) {
            log.error("No registered activity found for name: {}", activity.activityName());
            updateActivityAsFailed(activity, new RuntimeException("Activity not found: " + activity.activityName()));
            return;
        }

        // 2. 而⑦뀓?ㅽ듃 ?앹꽦
        // TODO: ActivityExecution ?곗씠?곕? 湲곕컲?쇰줈 ?ㅺ뎄??WorkflowContext瑜??앹꽦?섎뒗 ContextFactory ?곕룞 ?꾩슂
        // ?꾩옱???섎룞?쇰줈 ?앹꽦 (?ν썑 由ы뙥?좊쭅 ???
        DefaultWorkflowContext context = new DefaultWorkflowContext(
            activity.instanceId(),
            "UNKNOWN", // WorkflowName? Instance ?뚯씠釉?議고쉶媛 ?꾩슂?????덉쓬
            activity.activityName(),
            activity.retryCnt() + 1,
            activity.inputData()
        );
        context.attributes().put("executionId", activity.id());

        try {
            log.info("Executing activity: {} (Execution ID: {})", activity.activityName(), activity.id());
            
            // 3. 由ы뵆?됱뀡???듯븳 硫붿꽌???몄텧
            // ?낅젰 ?뚮씪誘명꽣媛 ?덉쓣 寃쎌슦 JSON ??쭅?ы솕 諛????留ㅼ묶 濡쒖쭅 蹂댁셿
            Object[] args = resolveArguments(metadata, activity.inputData());
            
            Object result = metadata.method().invoke(metadata.bean(), args);
            
            // 4. ?깃났 ??寃곌낵 ?낅뜲?댄듃 諛??ㅼ쓬 ?④퀎 泥섎━
            taskExecutor.complete(context, result);
            log.info("Activity completed: {} (Execution ID: {})", activity.activityName(), activity.id());
            
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException) ? e.getCause() : e;
            log.error("Failed to execute activity: " + activity.id(), cause);
            
            // 5. ?ㅽ뙣 ???ъ떆???뺤콉 ?곸슜
            int attempt = context.attempt();
            int maxRetry = metadata.annotation().retryCount();
            long baseBackoff = metadata.annotation().backoffSeconds();
            
            if (retryPolicy.isExceeded(attempt, maxRetry)) {
                log.error("Max retry count exceeded for activity: {}. Mark as FAILED.", activity.id());
                taskExecutor.fail(context, cause, null); // ???댁긽 ?ъ떆???놁쓬
            } else {
                Duration nextBackoff = retryPolicy.calculateNextBackoff(attempt, baseBackoff);
                log.info("Scheduling retry #{} for activity: {} with delay: {}s", attempt, activity.id(), nextBackoff.getSeconds());
                taskExecutor.fail(context, cause, nextBackoff);
            }
        }
    }

    /**
     * 由ы뵆?됱뀡 ?몄텧???꾪븳 ?뚮씪誘명꽣 諛붿씤??濡쒖쭅.
     * JSON ?낅젰 ?곗씠?곕? 硫붿꽌???뚮씪誘명꽣 ??낆뿉 留욊쾶 ??쭅?ы솕?⑸땲??
     */
    private Object[] resolveArguments(WorkflowRegistry.ActivityMetadata metadata, String inputData) {
        Class<?>[] parameterTypes = metadata.method().getParameterTypes();
        if (parameterTypes.length == 0) {
            return new Object[0];
        }
        
        // ?꾩옱??泥?踰덉㎏ ?뚮씪誘명꽣???낅젰??二쇱엯?섎뒗 寃껋쓣 湲곕낯?쇰줈 ??
        Object arg;
        Class<?> firstParamType = parameterTypes[0];
        
        if (firstParamType == String.class) {
            arg = inputData;
        } else {
            // engine-core??JsonUtil???ъ슜?섏뿬 ??쭅?ы솕 (WorkflowWorker???대? JsonUtil??媛꾩젒?곸쑝濡??쒖슜?섍굅??二쇱엯諛쏆쓣 ???덉쓬)
            // ?꾩옱 援ъ“?먯꽌??WorkflowWorker??JsonUtil 二쇱엯???꾨씫?섏뼱 ?덉쑝誘濡? ?꾩슂??寃쎌슦 異붽? 二쇱엯 ?꾩슂
            // ?ш린?쒕뒗 ?⑥닚?⑥쓣 ?꾪빐 String???꾨땲硫?null 泥섎━?섍굅???덉쇅瑜??섏쭏 ???덉쓬
            // TODO: WorkflowWorker??JsonUtil 二쇱엯 ???뺢탳????쭅?ы솕 援ы쁽
            arg = inputData; 
        }
        
        return new Object[]{arg};
    }

    private void updateActivityAsCompleted(ActivityExecution activity, Object output) {
        ActivityExecution completed = new ActivityExecution(
            activity.id(), activity.instanceId(), activity.activityName(),
            "COMPLETED", activity.inputData(), String.valueOf(output),
            null, null, activity.retryCnt(), null,
            activity.startDt(), LocalDateTime.now(),
            activity.useFl(), activity.viewFl(), activity.delFl(),
            activity.regDt(), activity.regId(), LocalDateTime.now(), "worker"
        );
        activityRepository.updateStatus(completed);
    }

    private void updateActivityAsFailed(ActivityExecution activity, Exception e) {
        // ?ㅼ젣濡쒕뒗 @Activity???ъ떆???뺤콉???쎌뼱 ?ㅼ쓬 ?ъ떆???쒓컙??怨꾩궛?댁빞 ??
        int nextRetryCnt = activity.retryCnt() + 1;
        LocalDateTime nextRetryDt = LocalDateTime.now().plus(Duration.ofSeconds(30)); // 30珥????ъ떆???덉떆

        ActivityExecution failed = new ActivityExecution(
            activity.id(), activity.instanceId(), activity.activityName(),
            "FAILED", activity.inputData(), null,
            e.getMessage(), stackTraceToString(e), nextRetryCnt, nextRetryDt,
            activity.startDt(), LocalDateTime.now(),
            activity.useFl(), activity.viewFl(), activity.delFl(),
            activity.regDt(), activity.regId(), LocalDateTime.now(), "worker"
        );
        activityRepository.updateStatus(failed);
    }

    private String stackTraceToString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}

