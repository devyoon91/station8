package com.bangrang.workflow.engine.dialect;

public interface DbDialect {
    /**
     * SKIP LOCKED? ?④퍡 ?ъ슜??LIMIT 荑쇰━ 議곌컖??諛섑솚?⑸땲??
     */
    String limit(int limit);

    /**
     * ?꾩옱 ?쒓컙 ?⑥닔瑜?諛섑솚?⑸땲??
     */
    String currentTimestamp();
}

