package com.bangrang.workflow.engine.dialect;

public class MariaDbDialect implements DbDialect {
    @Override
    public String limit(int limit) {
        return "LIMIT " + limit;
    }

    @Override
    public String currentTimestamp() {
        return "CURRENT_TIMESTAMP";
    }
}

