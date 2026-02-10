package com.bangrang.workflow.app.migration;

import com.bangrang.workflow.engine.annotation.Activity;
import com.bangrang.workflow.engine.annotation.Workflow;
import com.bangrang.workflow.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Workflow("DataMigrationWorkflow")
@Component
public class DataMigrationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationWorkflow.class);

    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;

    public DataMigrationWorkflow(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    /**
     * SRC_DATA????嫄댁쓣 ???DB ?뚯씠釉??ш린?쒕뒗 ?숈씪 DataSource??DEST_DATA)???곸옱?섍퀬 留덉씠洹몃젅?댁뀡 ?꾨즺濡?留덊궧?쒕떎.
     * ?낅젰? JSON 臾몄옄??{"id":"...", "content":"..."} ?뺤떇?대떎.
     *
     * 媛뺤젣 ?ㅻ쪟 ?좊컻: content??"Second"媛 ?ы븿?섎㈃ RuntimeException???섏졇 ?ъ떆??諛깆삤???숈옉??寃利앺븳??
     */
    @Activity(value = "MIGRATION_WRITE", retryCount = 5, backoffSeconds = 5)
    public String migrateItem(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            throw new IllegalArgumentException("Empty migration payload");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = jsonUtil.fromJson(inputJson, Map.class);
        String id = String.valueOf(payload.get("id"));
        String content = String.valueOf(payload.get("content"));

        // 媛뺤젣 ?먮윭 ?좊컻 (諛깆삤???ъ떆??寃利앹슜)
        if (content != null && content.contains("Second")) {
            log.warn("Forcing failure for content contains 'Second' (id={})", id);
            throw new RuntimeException("Forced failure for testing backoff");
        }

        // ????곸옱
        int inserted = jdbcTemplate.update("INSERT INTO DEST_DATA (ID, CONTENT) VALUES (?, ?)", id, content);
        // ?먮낯 留덊궧
        int updated = jdbcTemplate.update("UPDATE SRC_DATA SET MIGRATED_FL = 'Y' WHERE ID = ?", id);

        log.info("Migrated item id={}, inserted={}, markedUpdated={}", id, inserted, updated);
        return "OK:" + id;
    }
}

