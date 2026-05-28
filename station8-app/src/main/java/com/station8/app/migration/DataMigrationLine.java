package com.station8.app.migration;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.LineDefinition;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@LineDefinition("DataMigrationLine")
@Component
public class DataMigrationLine {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationLine.class);

    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;

    public DataMigrationLine(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    /**
     * SRC_DATA에서 건을 읽어 대상 DB 테이블(여기서는 동일 DataSource의 DEST_DATA)에 적재하고 마이그레이션 완료로 마킹한다.
     * 입력은 JSON 문자열 {"id":"...", "content":"..."} 형식이다.
     *
     * 강제 오류 유발: content에 "Second"가 포함되면 RuntimeException을 던져 재시도 백오프 동작을 검증한다.
     */
    @Activity(value = "MIGRATION_WRITE", retryCount = 1, backoffSeconds = 5,
            description = "SRC_DATA→DEST_DATA 단건 마이그레이션 — input은 {id, content} JSON. content에 'Second' 포함 시 강제 실패(데모).")
    public String migrateItem(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new IllegalArgumentException("Empty migration payload");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = jsonUtil.fromJson(inputJson, Map.class);
        String id = String.valueOf(payload.get("id"));
        String content = String.valueOf(payload.get("content"));

        // 강제 에러 유발 (백오프 재시도 검증용)
        if (content != null && content.contains("Second")) {
            log.warn("Forcing failure for content contains 'Second' (id={})", id);
            throw new RuntimeException("Forced failure for testing backoff");
        }

        // 타겟 적재
        int inserted = jdbcTemplate.update("INSERT INTO DEST_DATA (ID, CONTENT) VALUES (?, ?)", id, content);
        // 원본 마킹
        int updated = jdbcTemplate.update("UPDATE SRC_DATA SET MIGRATED_FL = 'Y' WHERE ID = ?", id);

        log.info("Migrated item id={}, inserted={}, markedUpdated={}", id, inserted, updated);
        return "OK:" + id;
    }
}

