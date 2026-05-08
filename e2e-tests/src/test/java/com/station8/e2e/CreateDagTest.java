package com.station8.e2e;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * 시나리오 01 포팅: 단일 역 DAG 정의 등록 → 조회 검증.
 *
 * <p>bash 스크립트 ``scripts/scenarios/01-create-dag.sh``의 REST Assured 버전.</p>
 */
class CreateDagTest extends E2EBaseTest {

    @Test
    @DisplayName("POST /api/line/definitions → 201 + GET으로 역 1개 검증")
    void createSingleNodeDag() {
        String name = "ScenarioFlow-" + System.currentTimeMillis();

        Map<String, Object> node = Map.of(
                "nodeId", "s1-n",
                "nodeNm", "Only",
                "activityNm", "NOOP",
                "inputParams", "scenario-1-payload",
                "posX", 100,
                "posY", 100
        );
        Map<String, Object> payload = Map.of(
                "definitionNm", name,
                "description", "e2e-tests CreateDagTest",
                "nodes", List.of(node),
                "edges", List.of()
        );

        Response createResp = given(SPEC)
                .body(payload)
                .when()
                .post("/api/line/definitions")
                .then()
                .statusCode(201)
                .body("definitionId", notNullValue())
                .extract().response();

        String definitionId = createResp.path("definitionId");
        assertThat(definitionId).as("definitionId 응답").isNotBlank();

        // GET으로 역 개수 확인
        given(SPEC)
                .when()
                .get("/api/line/definitions/" + definitionId)
                .then()
                .statusCode(200)
                .body("definitionNm", org.hamcrest.Matchers.equalTo(name))
                .body("nodes.size()", org.hamcrest.Matchers.equalTo(1))
                .body("nodes[0].activityNm", org.hamcrest.Matchers.equalTo("NOOP"));
    }
}
