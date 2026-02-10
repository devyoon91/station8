package com.bangrang.workflow.engine.repository;

import com.bangrang.workflow.engine.entity.ActivityExecution;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DB 湲곕컲 ?묒뾽 ???대쭅???꾪븳 由ы룷吏?좊━ ?명꽣?섏씠??
 */
public interface ActivityRepository {
    
    /**
     * Oracle/MariaDB??SKIP LOCKED瑜??ъ슜?섏뿬 泥섎━ 媛?ν븳 ?묒뾽??議고쉶?섍퀬 ?좉툑?⑸땲??
     *
     * @param limit 議고쉶??理쒕? ?묒뾽 ??
     * @return ?좉툑??Activity ?ㅽ뻾 紐⑸줉
     */
    List<ActivityExecution> findPendingActivitiesWithLock(int limit);
    
    /**
     * ?ㅽ뻾 ?곹깭 諛?寃곌낵瑜??낅뜲?댄듃?⑸땲??
     *
     * @param activityExecution ?낅뜲?댄듃???ㅽ뻾 ?뺣낫
     */
    void updateStatus(ActivityExecution activityExecution);

    /**
     * ?ㅼ쓬 ?④퀎 ?먮뒗 ?ъ떆???묒뾽??PENDING ?곹깭濡??앹꽦?⑸땲??
     *
     * @param instanceId ?뚰겕?뚮줈???몄뒪?댁뒪 ID
     * @param activityName ?≫떚鍮꾪떚 ?대쫫
     * @param inputData ?낅젰 JSON 臾몄옄??
     * @param nextRetryDt ?ㅼ쓬 ?ㅽ뻾(?ъ떆?? ?덉젙 ?쒓컖 (?놁쑝硫?利됱떆 ?ㅽ뻾 ??곸쑝濡?媛꾩＜)
     */
    void createPending(String instanceId, String activityName, String inputData, LocalDateTime nextRetryDt);

    /**
     * ?뚰겕?뚮줈???몄뒪?댁뒪 紐⑸줉??議고쉶?⑸땲??
     */
    List<com.bangrang.workflow.engine.entity.WorkflowInstance> findAllInstances();

    /**
     * ?뱀젙 ?몄뒪?댁뒪???곸꽭 ?뺣낫瑜?議고쉶?⑸땲??
     */
    com.bangrang.workflow.engine.entity.WorkflowInstance findInstanceById(String instanceId);

    /**
     * ?뱀젙 ?몄뒪?댁뒪???랁븳 ?≫떚鍮꾪떚 ?ㅽ뻾 ?대젰??議고쉶?⑸땲??
     */
    List<ActivityExecution> findActivitiesByInstanceId(String instanceId);

    /**
     * ?ㅽ뙣?섍굅??以묐떒???묒뾽???ㅼ떆 PENDING ?곹깭濡?蹂듦뎄?⑸땲??
     */
    void resetToPending(String executionId);
}

