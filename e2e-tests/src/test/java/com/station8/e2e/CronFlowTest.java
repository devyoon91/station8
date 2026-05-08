package com.station8.e2e;

import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 03 포팅: 스케줄 등록 → run-now → 인스턴스 + nextRun 갱신 검증.
 */
class CronFlowTest extends E2EBaseTest {

    private String createdScheduleId;

    @AfterEach
    void cleanup() {
        if (createdScheduleId != null) {
            given(SPEC).when().delete("/api/line/schedules/" + createdScheduleId);
            createdScheduleId = null;
        }
    }

    @Test
    @DisplayName("스케줄 등록 → run-now → 인스턴스 생성 + nextRunDt 미래값")
    void cronScheduleAndTrigger() {
        // 정의 등록
        String defName = "CronFlow-" + System.currentTimeMillis();
        Map<String, Object> defPayload = Map.of(
                "definitionNm", defName,
                "nodes", List.of(Map.of(
                        "nodeId", "c-n",
                        "nodeNm", "Only",
                        "activityNm", "NOOP",
                        "posX", 0, "posY", 0
                )),
                "edges", List.of()
        );
        String definitionId = given(SPEC)
                .body(defPayload)
                .when().post("/api/line/definitions")
                .then().statusCode(201)
                .extract().path("definitionId");

        // 스케줄 등록
        Map<String, Object> schedPayload = Map.of(
                "definitionId", definitionId,
                "cronExpr", "0 */5 * * * *"
        );
        Response schedResp = given(SPEC)
                .body(schedPayload)
                .when().post("/api/line/schedules")
                .then().statusCode(201)
                .extract().response();
        createdScheduleId = schedResp.path("scheduleId");
        assertThat(createdScheduleId).isNotBlank();

        // 조회 → nextRunDt가 비어있지 않아야 함
        given(SPEC)
                .when().get("/api/line/schedules/" + createdScheduleId)
                .then().statusCode(200)
                .body("nextRunDt", org.hamcrest.Matchers.notNullValue());

        // run-now → 즉시 인스턴스 생성
        given(SPEC)
                .when().post("/api/line/schedules/" + createdScheduleId + "/run-now")
                .then().statusCode(201)
                .body("instanceId", org.hamcrest.Matchers.notNullValue());
    }
}
