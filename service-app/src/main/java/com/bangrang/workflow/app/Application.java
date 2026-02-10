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

    // 媛꾨떒???곕え???⑥씪 DataSource (H2 ?먮뒗 MariaDB URL濡?援먯껜 媛??
    @Bean
    public DataSource dataSource() {
        // TODO: ?ㅽ솚寃쎌뿉?쒕뒗 application-*.yml ?꾨줈?뚯씪蹂??ㅼ젙?쇰줈 遺꾨━?섍퀬, Oracle/MariaDB ??媛쒖쓽 DataSource瑜?援ъ꽦?섏떗?쒖삤.
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
        // H2 ?명솚???꾪빐 MariaDB 諛⑹뼵 ?ъ슜 (CURRENT_TIMESTAMP, LIMIT 吏??
        return new MariaDbDialect();
    }
}

