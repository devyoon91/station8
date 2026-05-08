package com.station8.engine.dialect;

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

