package com.bangrang.workflow.app;

import com.bangrang.workflow.engine.dialect.DbDialect;
import com.bangrang.workflow.engine.dialect.MariaDbDialect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot 진입점.
 *
 * <p>DataSource/JdbcTemplate은 Spring Boot 자동 설정에 위임한다:
 * <ul>
 *   <li>default 프로파일: H2 임베디드 (h2 의존성 + spring.datasource.url 미명시)</li>
 *   <li>docker 프로파일: ``application-docker.properties``의 mariadb URL 또는 환경변수</li>
 * </ul>
 * </p>
 *
 * <p>이전에는 ``@Bean DataSource``가 H2 URL을 hardcode하고 있어 docker 프로파일에서도
 * mariadb 설정이 무시되는 #45 결함이 있었다.</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.bangrang.workflow.app", "com.bangrang.workflow.engine"})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * DAG 인터프리터/스케줄러가 사용하는 SQL 방언.
     * MariaDbDialect는 H2(MySQL 모드)와도 호환되므로 default/docker 프로파일 양쪽 모두 동작.
     */
    @Bean
    public DbDialect dbDialect() {
        return new MariaDbDialect();
    }
}
