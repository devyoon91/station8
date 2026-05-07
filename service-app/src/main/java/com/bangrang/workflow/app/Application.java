package com.bangrang.workflow.app;

import com.bangrang.workflow.engine.dialect.DbDialect;
import com.bangrang.workflow.engine.dialect.MariaDbDialect;
import com.bangrang.workflow.engine.dialect.OracleDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

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

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    /**
     * DAG 인터프리터/스케줄러가 사용하는 SQL 방언.
     *
     * <p>``spring.datasource.url``로 자동 판별:
     * <ul>
     *   <li>``oracle`` 포함 → {@link OracleDialect}</li>
     *   <li>그 외(mariadb/h2/mysql) → {@link MariaDbDialect} (H2 MySQL 모드와도 호환)</li>
     * </ul>
     * 명시 override가 필요하면 ``engine.dialect=oracle|mariadb`` 프로퍼티로 강제 지정 가능.</p>
     */
    @Bean
    public DbDialect dbDialect(Environment env) {
        String explicit = env.getProperty("engine.dialect", "").trim().toLowerCase();
        if ("oracle".equals(explicit)) {
            log.info("DbDialect: OracleDialect (explicit engine.dialect=oracle)");
            return new OracleDialect();
        }
        if ("mariadb".equals(explicit) || "mysql".equals(explicit) || "h2".equals(explicit)) {
            log.info("DbDialect: MariaDbDialect (explicit engine.dialect={})", explicit);
            return new MariaDbDialect();
        }
        String url = env.getProperty("spring.datasource.url", "").toLowerCase();
        if (url.contains("oracle")) {
            log.info("DbDialect: OracleDialect (auto-detected from datasource URL)");
            return new OracleDialect();
        }
        log.info("DbDialect: MariaDbDialect (default — also covers H2 MySQL mode)");
        return new MariaDbDialect();
    }
}
