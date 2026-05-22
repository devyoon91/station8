package com.station8.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 액티비티 메서드 파라미터에 station 단위 DataSource 바인딩을 주입하도록 표시 (#113).
 *
 * <p>예:</p>
 * <pre>{@code
 * @Activity("MIGRATE")
 * public String migrate(String input,
 *                       @BoundDataSource("source") JdbcTemplate src,
 *                       @BoundDataSource("target") JdbcTemplate dst) {
 *     // src/dst는 라인 정의 station의 datasourceBindings 매핑에 따라 결정
 * }
 * }</pre>
 *
 * <p>실행 시 {@code LineWorker}가 station을 조회해 {@code datasourceBindings} 맵에서
 * {@code value()}에 해당하는 키로 DataSource 이름을 찾고, {@code DataSourceRegistry}로부터
 * 해당 풀의 {@code JdbcTemplate}을 주입한다.</p>
 *
 * <p>매핑 누락(예: {@code @BoundDataSource("source")}인데 station에 'source' 키 없음) 또는
 * station 정보가 아예 없는 경우 → {@code primary} fallback (DM2). WARN 로그.</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface BoundDataSource {
    /** binding role 이름 (라인 정의의 station.datasourceBindings 키와 매칭). */
    String value();
}
