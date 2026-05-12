package com.station8.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Activity {
    String value() default "";
    int retryCount() default 3;
    long backoffSeconds() default 5;

    /**
     * 액티비티의 한 줄 설명 (#192). Builder 팔레트 카드 / Properties 패널 / Activities 카탈로그
     * 페이지에 노출되어 사용자가 의도를 코드 없이 파악할 수 있게 한다.
     *
     * <p>빈 문자열이면 UI에 표시되지 않는다 (선택 메타).</p>
     */
    String description() default "";
}

