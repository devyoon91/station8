package com.station8.engine.datasource;

import com.station8.engine.entity.DataSourceDefinition;
import com.station8.engine.repository.DataSourceDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 부팅 시 ``U_LINE_DATASOURCE``에 저장된 동적 DataSource 정의(#110)를 읽어
 * {@link DataSourceRegistry}에 등록한다.
 *
 * <p>{@link org.springframework.boot.context.event.ApplicationReadyEvent} 시점에 동작 —
 * DataSource autoconfig + 정적 secondary들이 모두 준비된 뒤 실행된다.</p>
 *
 * <p>이름 충돌 정책 (정적 win):</p>
 * <ul>
 *   <li>{@code primary}와 충돌 → 무시 + WARN</li>
 *   <li>STATIC(properties)과 충돌 → 무시 + WARN (DB 행은 그대로 두지만 활성화 안 함)</li>
 *   <li>그 외 → DYNAMIC으로 등록</li>
 * </ul>
 */
@Component
public class DynamicDataSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceLoader.class);

    private final DataSourceRegistry registry;
    private final DataSourceDefinitionRepository repository;
    private final JsonUtil jsonUtil;

    public DynamicDataSourceLoader(DataSourceRegistry registry,
                                   DataSourceDefinitionRepository repository,
                                   JsonUtil jsonUtil) {
        this.registry = registry;
        this.repository = repository;
        this.jsonUtil = jsonUtil;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        int loaded = 0, skipped = 0, failed = 0;
        for (DataSourceDefinition d : repository.findAll()) {
            if (!"Y".equals(d.enabledFl())) {
                log.info("DataSource '{}' is disabled — skipping load", d.name());
                skipped++;
                continue;
            }
            DataSourceRegistry.Source existing = registry.sourceOf(d.name());
            if (existing != DataSourceRegistry.Source.NONE) {
                log.warn("DataSource name '{}' already registered as {} — DB entry ignored (정적 우선)",
                        d.name(), existing);
                skipped++;
                continue;
            }
            try {
                Map<String, String> hikari = jsonUtil.fromJsonToStringMap(d.hikariOptions());
                DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                        d.name(), d.jdbcUrl(), d.username(), d.password(),
                        d.driverClass(), d.dialect(), hikari);
                DataSourceRegistry.TestResult result = registry.register(spec);
                if (result.success()) {
                    log.info("DataSource registered (DYNAMIC, healthy): {}", d.name());
                } else {
                    log.warn("DataSource registered (DYNAMIC, DOWN): {} — {}",
                            d.name(), result.errorMsg());
                }
                loaded++;
            } catch (Exception ex) {
                log.warn("Failed to register DYNAMIC DataSource '{}': {}",
                        d.name(), ex.getMessage());
                failed++;
            }
        }
        log.info("Dynamic DataSource load complete: {} loaded, {} skipped, {} failed",
                loaded, skipped, failed);
    }
}
