package com.station8.engine.datasource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 단일 ``station8.datasources.<name>.*`` 항목의 설정 모델.
 *
 * <p>키 매핑:
 * <pre>
 * station8.datasources.source-oracle.url            -&gt; url
 * station8.datasources.source-oracle.username       -&gt; username
 * station8.datasources.source-oracle.password       -&gt; password
 * station8.datasources.source-oracle.driver-class-name -&gt; driverClassName
 * station8.datasources.source-oracle.dialect        -&gt; dialect (mariadb|oracle)
 * station8.datasources.source-oracle.hikari.maximum-pool-size -&gt; hikari["maximum-pool-size"]
 * </pre>
 */
public class DataSourceEntry {
    private String url;
    private String username;
    private String password;
    private String driverClassName;

    /** 명시적 dialect override. 미지정 시 URL에서 추론(D6). */
    private String dialect;

    /** Hikari 옵션 raw map — D5: ``station8.datasources.<name>.hikari.*``로 override. */
    private Map<String, String> hikari = new LinkedHashMap<>();

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }

    public Map<String, String> getHikari() { return hikari; }
    public void setHikari(Map<String, String> hikari) { this.hikari = hikari; }
}
