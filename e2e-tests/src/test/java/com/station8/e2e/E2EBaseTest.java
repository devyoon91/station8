package com.station8.e2e;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.time.Duration;

/**
 * 모든 e2e 테스트의 기반.
 *
 * <p>실행 전제: docker compose로 station8-app이 떠 있고 시스템 프로퍼티 ``swe.e2e.host``로 호스트:포트 주입.
 * 빌드 스크립트(`e2e-tests/build.gradle`)에서 -PdockerHost / SWE_E2E_HOST 환경변수 → systemProperty로 매핑.</p>
 *
 * <p>BeforeAll에서 헬스체크 한 번 → 응답 없으면 빠르게 실패.</p>
 */
@Tag("e2e")
public abstract class E2EBaseTest {

    protected static final String BASE_URL = resolveBaseUrl();
    protected static final RequestSpecification SPEC = new RequestSpecBuilder()
            .setBaseUri(BASE_URL)
            .setContentType(ContentType.JSON)
            .build();

    @BeforeAll
    static void healthCheck() {
        RestAssured.baseURI = BASE_URL;
        // station8-app은 actuator 미장착 — 랜딩 페이지 ``/``로 readiness 확인
        Awaitility.await("station8-app readiness")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> RestAssured.given()
                        .baseUri(BASE_URL)
                        .when()
                        .get("/")
                        .statusCode() < 500);
    }

    private static String resolveBaseUrl() {
        String host = System.getProperty("swe.e2e.host");
        if (host == null || host.isBlank()) {
            host = System.getenv("SWE_E2E_HOST");
        }
        if (host == null || host.isBlank()) {
            // 빌드 스크립트에서 onlyIf로 막히지만, 직접 IDE에서 돌릴 때 fallback
            host = "localhost:8080";
        }
        if (!host.startsWith("http")) {
            host = "http://" + host;
        }
        return host;
    }
}
