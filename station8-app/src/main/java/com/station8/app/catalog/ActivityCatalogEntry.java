package com.station8.app.catalog;

import java.util.List;

/**
 * 액티비티 카탈로그 노출 DTO. ``Method`` 객체를 직접 노출하지 않고 직렬화 안전한 필드만 추출한다.
 */
public record ActivityCatalogEntry(
        String activityName,
        String beanClass,
        String methodName,
        int retryCount,
        long backoffSeconds,
        List<String> parameterTypes,
        String returnType,
        /** #192 — @Activity(description) 메타. 빈 문자열이면 UI에서 숨김. */
        String description,
        /**
         * #304 — @Activity(params) 메타. 빌더가 schema 기반 form 렌더에 사용.
         * 빈 list면 빌더는 free-form textarea fallback.
         */
        List<ParamSchema> params
) {

    /**
     * 단일 파라미터 schema. {@code com.station8.engine.annotation.ActivityParam} 어노테이션의
     * runtime 표현 — 직렬화 안전한 필드만.
     *
     * @param name          inputParams JSON object의 키
     * @param kind          입력 종류 (STRING/NUMBER/BOOLEAN/SELECT/OBJECT/CREDENTIAL). enum name 문자열로
     * @param required      true면 빌더가 빈 값 저장 차단 + UI에 * 표시
     * @param description   form 헬프 텍스트
     * @param options       SELECT은 dropdown 옵션, CREDENTIAL은 호환 type 화이트리스트
     * @param defaultValue  form 초기값
     */
    public record ParamSchema(
            String name,
            String kind,
            boolean required,
            String description,
            List<String> options,
            String defaultValue
    ) {}
}
