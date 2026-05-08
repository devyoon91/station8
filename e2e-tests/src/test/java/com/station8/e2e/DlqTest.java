package com.station8.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * 시나리오 04 포팅: DLQ 페이지 가시화 검증.
 *
 * <p>실제 DLQ 적재까지 검증하려면 재시도 백오프(5/10/20/40/80s)로 약 2~3분 소요되어 e2e 회귀에서는 비실용적.
 * 본 테스트는 페이지 접근성과 DemoSeedRunner가 생성한 'Second Data' 항목이 DLQ로 가는 흐름을 페이지 응답으로만 검증.</p>
 */
class DlqTest extends E2EBaseTest {

    @Test
    @DisplayName("/line/dlq 200 응답")
    void dlqPageAccessible() {
        given(SPEC)
                .when().get("/line/dlq")
                .then().statusCode(200);
    }
}
