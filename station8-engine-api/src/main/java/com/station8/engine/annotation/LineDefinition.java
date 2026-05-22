package com.station8.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
/**
 * 클래스 단위로 라인(워크플로우)을 정의하는 마커 어노테이션.
 *
 * <p>{@code value}는 등록되는 라인 이름이며, 비어 있으면 클래스명을 사용한다.
 *
 * <pre>{@code
 * @LineDefinition("OrderFlow")
 * public class OrderFlow {
 *     @Activity("validate")
 *     public String validate(String input) { ... }
 * }
 * }</pre>
 */
public @interface LineDefinition {
    String value() default "";
}

