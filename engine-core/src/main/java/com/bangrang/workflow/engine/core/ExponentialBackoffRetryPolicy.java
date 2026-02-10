package com.bangrang.workflow.engine.core;

import java.time.Duration;

/**
 * 吏??諛깆삤??湲곕컲???ъ떆??吏???쒓컙??怨꾩궛?섎뒗 ?대옒??
 */
public class ExponentialBackoffRetryPolicy {

    /**
     * ?쒕룄 ?잛닔???곕Ⅸ ?ㅼ쓬 吏???쒓컙??怨꾩궛?⑸땲??
     * 怨듭떇: baseBackoffSeconds * (2 ^ (attempt - 1))
     *
     * @param attempt ?꾩옱 ?쒕룄 ?잛닔 (1遺???쒖옉)
     * @param baseBackoffSeconds 湲곕낯 諛깆삤??珥??⑥쐞
     * @return 怨꾩궛??吏???쒓컙
     */
    public Duration calculateNextBackoff(int attempt, long baseBackoffSeconds) {
        if (attempt <= 1) {
            return Duration.ofSeconds(baseBackoffSeconds);
        }
        
        // 吏??怨꾩궛 (2??n?쒓낢)
        long exponentialFactor = (long) Math.pow(2, attempt - 1);
        long delaySeconds = baseBackoffSeconds * exponentialFactor;
        
        // 理쒕? 吏???쒓컙 ?쒗븳 (?? 1?쒓컙) - ?꾩슂 ???뚮씪誘명꽣??媛??
        long maxDelaySeconds = 3600;
        return Duration.ofSeconds(Math.min(delaySeconds, maxDelaySeconds));
    }

    /**
     * 理쒕? ?ъ떆???잛닔瑜?珥덇낵?덈뒗吏 ?뺤씤?⑸땲??
     *
     * @param attempt ?꾩옱 ?쒕룄 ?잛닔
     * @param maxRetryCount 理쒕? ?덉슜 ?ъ떆???잛닔
     * @return 珥덇낵 ?щ?
     */
    public boolean isExceeded(int attempt, int maxRetryCount) {
        return attempt > maxRetryCount;
    }
}

