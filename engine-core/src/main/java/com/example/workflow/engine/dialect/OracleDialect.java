package com.example.workflow.engine.dialect;

public class OracleDialect implements DbDialect {
    @Override
    public String limit(int limit) {
        return "FETCH FIRST " + limit + " ROWS ONLY";
    }

    @Override
    public String currentTimestamp() {
        return "CURRENT_TIMESTAMP";
    }
}
