package com.station8.engine.datasource;

import com.station8.engine.dialect.DbDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 멀티 DataSource 설정. {@link Station8DataSourcesProperties}로부터 secondary 풀들을 빌드한다.
 *
 * <h3>Primary 동작 (D2(c))</h3>
 * <ul>
 *   <li>``station8.datasources.primary.url``이 정의되면 → 본 클래스가 ``@Primary DataSource``
 *       빈을 직접 등록 (Spring autoconfig는 {@code @ConditionalOnMissingBean(DataSource.class)}
 *       로 자동 skip).</li>
 *   <li>아니면 → Spring Boot의 {@code DataSourceAutoConfiguration}이 ``spring.datasource.*``로
 *       primary를 만든다. 본 클래스는 그것을 받아 레지스트리에 등록만 한다.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(Station8DataSourcesProperties.class)
public class DataSourceConfig {

    /**
     * ``station8.datasources.primary.url``이 설정된 경우만 primary DataSource를 직접 빌드.
     * Spring autoconfig를 preempt한다.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "station8.datasources.primary", name = "url")
    public DataSource station8PrimaryDataSource(Station8DataSourcesProperties props) {
        DataSourceEntry entry = props.getDatasources().get("primary");
        if (entry == null || entry.getUrl() == null || entry.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "station8.datasources.primary.url is required when defining primary via station8.datasources.primary");
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("station8-primary");
        cfg.setJdbcUrl(entry.getUrl());
        if (entry.getUsername() != null) cfg.setUsername(entry.getUsername());
        if (entry.getPassword() != null) cfg.setPassword(entry.getPassword());
        if (entry.getDriverClassName() != null && !entry.getDriverClassName().isBlank()) {
            cfg.setDriverClassName(entry.getDriverClassName());
        }
        return new HikariDataSource(cfg);
    }

    /**
     * 멀티 DataSource 레지스트리. primary는 외부(Spring autoconfig 또는 위 ``station8PrimaryDataSource``)
     * 에서 주입받고, secondary들은 ``station8.datasources.<name>.*``로부터 본 빈에서 빌드한다.
     *
     * <p>``primary`` URL/username은 {@link Station8DataSourcesProperties}에 없을 수 있으므로
     * (D2(c) fallback) ``spring.datasource.*`` env 값을 함께 주입해 어드민 UI에 표시.</p>
     */
    @Bean(destroyMethod = "close")
    public DataSourceRegistry dataSourceRegistry(
            DataSource primary,
            DbDialect primaryDialect,
            Station8DataSourcesProperties props,
            @Value("${spring.datasource.url:}") String springDsUrl,
            @Value("${spring.datasource.username:}") String springDsUsername) {
        return new DefaultDataSourceRegistry(primary, primaryDialect, props, springDsUrl, springDsUsername);
    }
}
