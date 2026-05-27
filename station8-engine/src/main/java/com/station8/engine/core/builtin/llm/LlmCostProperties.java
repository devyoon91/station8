package com.station8.engine.core.builtin.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 모델별 단가 설정 (#339). 단가는 자주 바뀌므로 상수로 박지 않고 properties로 둔다.
 *
 * <pre>{@code
 * # application.properties — 100만 토큰당 USD
 * station8.llm.pricing.gpt-4o.input-per1m=2.50
 * station8.llm.pricing.gpt-4o.output-per1m=10.00
 * # 모델명에 점이 있으면 bracket 표기: station8.llm.pricing[gpt-4.1].input-per1m=...
 * }</pre>
 *
 * <p>설정에 없는 모델(사내 파인튜닝 등)은 비용 NULL — 토큰 수는 그대로 기록된다.</p>
 */
@Component
@ConfigurationProperties(prefix = "station8.llm")
public class LlmCostProperties {

    private Map<String, ModelPrice> pricing = new LinkedHashMap<>();

    public Map<String, ModelPrice> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, ModelPrice> pricing) {
        this.pricing = pricing;
    }

    /** 모델 1종의 입력/출력 단가 (100만 토큰당 USD). */
    public static class ModelPrice {
        private BigDecimal inputPer1m;
        private BigDecimal outputPer1m;

        public BigDecimal getInputPer1m() {
            return inputPer1m;
        }

        public void setInputPer1m(BigDecimal inputPer1m) {
            this.inputPer1m = inputPer1m;
        }

        public BigDecimal getOutputPer1m() {
            return outputPer1m;
        }

        public void setOutputPer1m(BigDecimal outputPer1m) {
            this.outputPer1m = outputPer1m;
        }
    }
}
