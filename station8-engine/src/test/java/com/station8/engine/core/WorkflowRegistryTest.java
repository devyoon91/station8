package com.station8.engine.core;

import com.station8.engine.annotation.Activity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowRegistry의 멱등 등록 동작 검증.
 *
 * <p>회귀 방지 컨텍스트: CGLIB 프록시 빈을 스캔할 때 ``ReflectionUtils.doWithMethods``가
 * 프록시 클래스 + 부모(원본) 클래스를 모두 방문해 동일 ``@Activity``가 두 번 등록되던
 * 결함이 있었음. 수정: {@code AopUtils.getTargetClass()}로 원본 클래스만 스캔 +
 * ``activityMap.containsKey`` 멱등 가드.</p>
 *
 * <p>본 테스트는 ``registerActivity()`` public API 레벨에서 멱등성을 확인한다. 전체 Spring
 * AOP 시나리오는 e2e 테스트(NoopWorkflow 등록 로그)에서 검증.</p>
 */
class WorkflowRegistryTest {

    @Test
    void registerActivity_skipsDuplicateName() throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        ProbeBean bean = new ProbeBean();
        Method m = ProbeBean.class.getMethod("doStuff", String.class);
        Activity ann = m.getAnnotation(Activity.class);

        registry.registerActivity("FOO", bean, m, ann);
        registry.registerActivity("FOO", bean, m, ann); // 충돌 — 코어 우선, 무시
        registry.registerActivity("BAR", bean, m, ann);

        assertThat(registry.getActivityNames())
                .containsExactlyInAnyOrder("FOO", "BAR");
    }

    @Test
    void getActivities_returnsUnmodifiableMap() {
        WorkflowRegistry registry = new WorkflowRegistry();
        // 빈 상태에서도 unmodifiable 보장
        assertThat(registry.getActivities()).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> registry.getActivities().put("X", null));
    }

    public static class ProbeBean {
        @Activity("FOO")
        public String doStuff(String input) {
            return "ok:" + input;
        }
    }
}
