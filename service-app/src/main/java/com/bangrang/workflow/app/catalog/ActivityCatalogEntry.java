package com.bangrang.workflow.app.catalog;

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
        String returnType
) {
}
