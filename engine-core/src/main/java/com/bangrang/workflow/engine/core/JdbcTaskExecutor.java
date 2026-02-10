package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import com.bangrang.workflow.engine.repository.ActivityRepository;
import com.bangrang.workflow.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * TaskExecutor??JDBC 湲곕컲 湲곕낯 援ы쁽泥?
 * - ?꾩옱 ?≫떚鍮꾪떚???깃났/?ㅽ뙣 寃곌낵瑜?DB(H_WF_ACTIVITY_EXECUTION)??諛섏쁺
 * - 而⑦뀓?ㅽ듃??吏?뺣맂 ?ㅼ쓬 ?≫떚鍮꾪떚媛 ?덉쑝硫?PENDING?쇰줈 ?앹꽦?섏뿬 ?ㅼ?以꾨쭅
 */
public class JdbcTaskExecutor implements TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskExecutor.class);

    private static final String CTX_KEY_EXECUTION_ID = "executionId"; // WorkflowWorker媛 而⑦뀓?ㅽ듃??二쇱엯?댁빞 ??
    private static final String CTX_KEY_INSTANCE_ID = "instanceId";   // ?꾩슂 ??二쇱엯(?놁쑝硫?context.instanceId()) ?ъ슜

    private final ActivityRepository activityRepository;
    private final JsonUtil jsonUtil;

    public JdbcTaskExecutor(ActivityRepository activityRepository, JsonUtil jsonUtil) {
        this.activityRepository = activityRepository;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public void executeCurrent(WorkflowContext context) {
        // ?ㅼ젣 鍮꾩쫰?덉뒪 硫붿꽌???몄텧? WorkflowWorker/Invoker ?덉씠?댁뿉???섑뻾.
        // 蹂?援ы쁽泥대뒗 ?ㅽ뻾 寃곌낵瑜?諛섏쁺?섍퀬 ?ㅼ쓬 ?④퀎 ?ㅼ?以꾨쭅???대떦?섎?濡? ?ш린?쒕뒗 蹂꾨룄 ?숈옉 ?놁쓬.
        log.debug("executeCurrent called for activity: {}", context.currentActivityName());
    }

    @Override
    public void scheduleNext(WorkflowContext context, String nextActivityName, Object input) {
        String instanceId = resolveInstanceId(context);
        String inputJson = jsonUtil.toJson(input);
        activityRepository.createPending(instanceId, nextActivityName, inputJson, null);
        log.info("Scheduled next activity '{}' for instance {}", nextActivityName, instanceId);
    }

    @Override
    public void complete(WorkflowContext context, Object output) {
        String executionId = resolveExecutionId(context);
        String outputJson = jsonUtil.toJson(output);
        ActivityExecution updated = new ActivityExecution(
            executionId,
            context.instanceId(),
            context.currentActivityName(),
            "COMPLETED",
            null,
            outputJson,
            null,
            null,
            0,
            null,
            null,
            LocalDateTime.now(),
            null,
            null,
            null,
            null,
            null,
            null
        );
        activityRepository.updateStatus(updated);

        // 而⑦뀓?ㅽ듃??next ?뚰듃媛 議댁옱?섎㈃ ?ㅼ?以꾨쭅
        context.nextActivityName().ifPresent(name -> {
            Object nextInput = context.nextActivityInput().orElse(null);
            scheduleNext(context, name, nextInput);
        });
    }

    @Override
    public void fail(WorkflowContext context, Throwable error, Duration nextBackoff) {
        String executionId = resolveExecutionId(context);
        String errorMessage = error.getMessage();
        String stackTrace = buildStackTrace(error);
        LocalDateTime nextRetry = (nextBackoff != null) ? LocalDateTime.now().plusSeconds(nextBackoff.getSeconds()) : null;

        ActivityExecution updated = new ActivityExecution(
            executionId,
            context.instanceId(),
            context.currentActivityName(),
            "FAILED",
            null,
            null,
            errorMessage,
            stackTrace,
            context.attempt(),
            nextRetry,
            null,
            LocalDateTime.now(),
            null,
            null,
            null,
            null,
            null,
            null
        );
        activityRepository.updateStatus(updated);

        // ?ъ떆???뺤콉???ш린??吏곸젒 ?앹꽦?섏????딆쓬(?뺤콉 ?댁꽍? ?곸쐞 ?덉씠?댁뿉??.
        // TODO: ?뺤콉???곕Ⅸ 理쒕? ?ъ떆???잛닔 ?꾨떖 ??理쒖쥌 FAILED 泥섎━ 諛??뚮┝ 諛쒖넚 濡쒖쭅 寃??
        // ?꾩슂 ???ㅼ쓬怨?媛숈씠 ?숈씪 ?≫떚鍮꾪떚 ?ъ떆???덉퐫?쒕? ?앹꽦?????덉쓬:
        if (nextRetry != null) {
            activityRepository.createPending(context.instanceId(), context.currentActivityName(), jsonUtil.toJson(context.input()), nextRetry);
        }
    }

    @Override
    public void checkpoint(WorkflowContext context, Object stateSnapshot) {
        // 泥댄겕?ъ씤?몃뒗 ?몄뒪?댁뒪 ?덈꺼( U_WF_INSTANCE.STATE_DATA )????λ릺硫?
        // 蹂?援ы쁽?먯꽌??而⑦뀓?ㅽ듃媛 蹂댁쑀???ㅻ깄??吏곷젹??湲곕뒫(saveState)???쒖슜?쒕떎.
        context.saveState(stateSnapshot);
    }

    private String resolveExecutionId(WorkflowContext context) {
        return Optional.ofNullable(context.attributes().get(CTX_KEY_EXECUTION_ID))
                .map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("Missing executionId in WorkflowContext.attributes"));
    }

    private String resolveInstanceId(WorkflowContext context) {
        Object v = context.attributes().get(CTX_KEY_INSTANCE_ID);
        return v != null ? v.toString() : context.instanceId();
    }

    private String buildStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append("\n");
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\tat ").append(e).append("\n");
        }
        return sb.toString();
    }
}

