package com.station8.engine.core.builtin.llm;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 토큰 사용량 → 추정 비용(USD) 계산 (#339). 단가는 {@link LlmCostProperties}에서 모델별로 조회.
 *
 * <p>단가 미설정 모델은 {@code null} 반환 — 호출부는 {@code estimated_cost_usd}에 NULL을 기록하고
 * 토큰 수는 그대로 남긴다.</p>
 */
@Component
public class LlmCostCalculator {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final int SCALE = 6;

    private final LlmCostProperties properties;

    public LlmCostCalculator(LlmCostProperties properties) {
        this.properties = properties;
    }

    /**
     * 추정 비용 계산. {@code (inputTokens/1M * inputPer1m) + (outputTokens/1M * outputPer1m)}.
     *
     * @param model 모델 식별자
     * @param usage 토큰 사용량
     * @return USD 비용 (scale 6). 단가 미설정 모델이면 null
     */
    public BigDecimal estimate(String model, LlmUsage usage) {
        if (model == null || usage == null) {
            return null;
        }
        LlmCostProperties.ModelPrice price = properties.getPricing().get(model);
        if (price == null || price.getInputPer1m() == null || price.getOutputPer1m() == null) {
            return null;
        }
        BigDecimal inputCost = price.getInputPer1m()
                .multiply(BigDecimal.valueOf(usage.inputTokens()))
                .divide(ONE_MILLION, SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = price.getOutputPer1m()
                .multiply(BigDecimal.valueOf(usage.outputTokens()))
                .divide(ONE_MILLION, SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
