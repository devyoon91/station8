package com.station8.engine.dialect;

public class OracleDialect implements DbDialect {
    @Override
    public String limit(int limit) {
        return "FETCH FIRST " + limit + " ROWS ONLY";
    }

    @Override
    public String offsetLimit(int offset, int limit) {
        return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String currentTimestamp() {
        return "CURRENT_TIMESTAMP";
    }
}

