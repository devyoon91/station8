package com.station8.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * 시나리오 05 포팅: DAG 검증 실패 응답 코드 + errorCode 검증.
 */
class ValidationErrorsTest extends E2EBaseTest {

    @Test
    @DisplayName("사이클 DAG → 400 + WF-E305")
    void cycleDagRejected() {
        Map<String, Object> payload = Map.of(
                "definitionNm", "CycleFlow-" + System.currentTimeMillis(),
                "nodes", List.of(
                        Map.of("nodeId", "ca", "nodeNm", "A", "activityNm", "NOOP", "posX", 0, "posY", 0),
                        Map.of("nodeId", "cb", "nodeNm", "B", "activityNm", "NOOP", "posX", 0, "posY", 0)
                ),
                "edges", List.of(
                        Map.of("edgeId", "ce1", "fromNodeId", "ca", "toNodeId", "cb"),
                        Map.of("edgeId", "ce2", "fromNodeId", "cb", "toNodeId", "ca")
                )
        );

        given(SPEC)
                .body(payload)
                .when().post("/api/workflow/definitions")
                .then()
                .statusCode(400)
                .body("message", containsString("WF-E305"));
    }

    @Test
    @DisplayName("미등록 액티비티 → 400 + WF-E307")
    void unknownActivityRejected() {
        Map<String, Object> payload = Map.of(
                "definitionNm", "UnknownAct-" + System.currentTimeMillis(),
                "nodes", List.of(Map.of(
                        "nodeId", "u1", "nodeNm", "X",
                        "activityNm", "NO_SUCH_ACTIVITY",
                        "posX", 0, "posY", 0
                )),
                "edges", List.of()
        );

        given(SPEC)
                .body(payload)
                .when().post("/api/workflow/definitions")
                .then()
                .statusCode(400)
                .body("message", containsString("WF-E307"));
    }

    @Test
    @DisplayName("정상 DAG는 201")
    void validDagAccepted() {
        Map<String, Object> payload = Map.of(
                "definitionNm", "OK-" + System.currentTimeMillis(),
                "nodes", List.of(Map.of(
                        "nodeId", "ok1", "nodeNm", "A",
                        "activityNm", "NOOP",
                        "posX", 0, "posY", 0
                )),
                "edges", List.of()
        );

        given(SPEC)
                .body(payload)
                .when().post("/api/workflow/definitions")
                .then().statusCode(201);
    }
}
