package com.station8.engine.datasource;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ``station8.datasources.<name>.*`` 형태의 멀티 DataSource 선언을 바인딩한다.
 *
 * <p>D1 결정에 따라 prefix는 ``station8`` (브랜드 prefix). Map 키는 DataSource 이름.</p>
 *
 * <p>D2(c) 후방 호환:
 * <ul>
 *   <li>``station8.datasources.primary.*``가 정의되면 그것이 primary</li>
 *   <li>아니면 Spring Boot가 ``spring.datasource.*``로 자동 구성한 DataSource를 primary로 사용</li>
 * </ul>
 * 둘 다 정의되면 ``station8.datasources.primary``가 이긴다 — Spring autoconfig는
 * {@code @ConditionalOnMissingBean(DataSource.class)}로 skip된다.
 * </p>
 */
@ConfigurationProperties(prefix = "station8")
public class Station8DataSourcesProperties {

    /** Map<name, entry>. ``station8.datasources.foo.url``이 ``datasources.get("foo").url``로 바인딩됨. */
    private final Map<String, DataSourceEntry> datasources = new LinkedHashMap<>();

    public Map<String, DataSourceEntry> getDatasources() {
        return datasources;
    }
}
