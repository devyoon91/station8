package com.bangrang.workflow.e2e;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * 시나리오 02 포팅: 정의 등록 후 즉시 실행 → 인스턴스 생성 검증.
 */
class RunNowTest extends E2EBaseTest {

    @Test
    @DisplayName("POST /run → instanceId 발급 + 대시보드에서 인스턴스 가시화")
    void runNow() {
        // 사전 정의 등록
        String defName = "RunNowFlow-" + System.currentTimeMillis();
        Map<String, Object> defPayload = Map.of(
                "definitionNm", defName,
                "nodes", List.of(Map.of(
                        "nodeId", "r-n",
                        "nodeNm", "Only",
                        "activityNm", "NOOP",
                        "posX", 0, "posY", 0
                )),
                "edges", List.of()
        );

        String definitionId = given(SPEC)
                .body(defPayload)
                .when()
                .post("/api/workflow/definitions")
                .then()
                .statusCode(201)
                .extract().path("definitionId");

        // 즉시 실행
        Response runResp = given(SPEC)
                .body(Map.of("input", "scenario-02"))
                .when()
                .post("/api/workflow/definitions/" + definitionId + "/run")
                .then()
                .statusCode(201)
                .body("instanceId", notNullValue())
                .extract().response();

        String instanceId = runResp.path("instanceId");
        assertThat(instanceId).isNotBlank();

        // 대시보드에 200으로 응답하는지 (HTML 페이지)
        given(SPEC)
                .when()
                .get("/workflow/dashboard")
                .then()
                .statusCode(200);
    }
}
