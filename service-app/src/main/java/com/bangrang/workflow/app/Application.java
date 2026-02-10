package com.bangrang.workflow.app;

import com.bangrang.workflow.engine.dialect.DbDialect;
import com.bangrang.workflow.engine.dialect.MariaDbDialect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@SpringBootApplication
@ComponentScan(basePackages = {"com.bangrang.workflow.app", "com.bangrang.workflow.engine"})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    // 간단한 데모용 단일 DataSource (H2 또는 MariaDB URL로 교체 가능)
    @Bean
    public DataSource dataSource() {
        // TODO: 실환경에서는 application-*.yml 프로파일별 설정으로 분리하고, Oracle/MariaDB 두 개의 DataSource를 구성하십시오.
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:demo;MODE=MariaDB;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DbDialect dbDialect() {
        // H2 호환을 위해 MariaDB 방언 사용 (CURRENT_TIMESTAMP, LIMIT 지원)
        return new MariaDbDialect();
    }
}

