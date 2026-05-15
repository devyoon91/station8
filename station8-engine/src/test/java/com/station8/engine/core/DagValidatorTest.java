package com.station8.engine.core;

import com.station8.engine.entity.LineTrack;
import com.station8.engine.entity.LineStation;
import com.station8.engine.exception.ErrorCodes;
import com.station8.engine.exception.LineEngineException;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DagValidator 단위 테스트 — 각 위반 코드별 검증.
 */
class DagValidatorTest {

    private final DagValidator validator = new DagValidator(new EdgeConditionEvaluator(new JsonUtil()));
    private final Set<String> registered = Set.of("A", "B", "C", "D");

    @Test
    void validDag_diamond_passes() {
        // A → B, A → C, B → D, C → D
        List<LineStation> nodes = List.of(
                node("n-a", "A"), node("n-b", "B"), node("n-c", "C"), node("n-d", "D")
        );
        List<LineTrack> edges = List.of(
                edge("e1", "n-a", "n-b"),
                edge("e2", "n-a", "n-c"),
                edge("e3", "n-b", "n-d"),
                edge("e4", "n-c", "n-d")
        );
        assertDoesNotThrow(() -> validator.validate(nodes, edges, registered));
    }

    @Test
    void empty_nodes_throws_NO_NODES() {
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(List.of(), List.of(), registered));
        assertEquals(ErrorCodes.DAG_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_NO_NODES));
    }

    @Test
    void cycle_two_nodes_throws_CYCLE_DETECTED() {
        // A → B, B → A
        List<WrongCase.NodeEdge> data = WrongCase.cycle();
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(data.get(0).nodes(), data.get(0).edges(), registered));
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_CYCLE_DETECTED), ex.getMessage());
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_NO_START_NODE), ex.getMessage());
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_NO_TERMINAL_NODE), ex.getMessage());
    }

    @Test
    void self_loop_throws_SELF_LOOP_and_CYCLE() {
        // A → A
        List<LineStation> nodes = List.of(node("n-a", "A"));
        List<LineTrack> edges = List.of(edge("e1", "n-a", "n-a"));
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(nodes, edges, registered));
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_SELF_LOOP), ex.getMessage());
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_CYCLE_DETECTED), ex.getMessage());
    }

    @Test
    void unknown_activity_throws_UNKNOWN_ACTIVITY() {
        List<LineStation> nodes = List.of(node("n-a", "A"), node("n-x", "UNREGISTERED"));
        List<LineTrack> edges = List.of(edge("e1", "n-a", "n-x"));
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(nodes, edges, registered));
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_UNKNOWN_ACTIVITY), ex.getMessage());
        assertTrue(ex.getMessage().contains("UNREGISTERED"), ex.getMessage());
    }

    @Test
    void unknown_activity_skipped_when_registered_set_is_null() {
        List<LineStation> nodes = List.of(node("n-a", "WHATEVER"));
        // 단일 역 — 시작이자 종료, registered=null이라 미등록 검증 생략
        assertDoesNotThrow(() -> validator.validate(nodes, List.of(), null));
    }

    @Test
    void dangling_edge_throws_DANGLING_EDGE() {
        List<LineStation> nodes = List.of(node("n-a", "A"));
        List<LineTrack> edges = List.of(edge("e1", "n-a", "n-ghost"));
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(nodes, edges, registered));
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_DANGLING_EDGE), ex.getMessage());
    }

    @Test
    void all_nodes_have_incoming_throws_NO_START_NODE() {
        // A → B → C → A 의 일부만 — A,B,C 모두 incoming≥1
        List<LineStation> nodes = List.of(node("n-a", "A"), node("n-b", "B"), node("n-c", "C"));
        List<LineTrack> edges = List.of(
                edge("e1", "n-a", "n-b"),
                edge("e2", "n-b", "n-c"),
                edge("e3", "n-c", "n-a")
        );
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(nodes, edges, registered));
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_NO_START_NODE), ex.getMessage());
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_CYCLE_DETECTED), ex.getMessage());
    }

    @Test
    void single_node_passes() {
        // A 단일 역 (시작이자 종료, 사이클 없음)
        List<LineStation> nodes = List.of(node("n-a", "A"));
        assertDoesNotThrow(() -> validator.validate(nodes, List.of(), registered));
    }

    // ---- #152 — 엣지 conditionExpr SpEL 컴파일 검증 ----

    @Test
    void valid_conditionExpr_passes() {
        // SpEL 문법 OK — 조건이 있어도 정상 통과
        List<LineStation> nodes = List.of(node("n-a", "A"), node("n-b", "B"));
        LineTrack edge = new LineTrack("e1", "def-test", "n-a", "n-b",
                "#result['success'] == true",
                "N", null, null, null, null);
        assertDoesNotThrow(() -> validator.validate(nodes, List.of(edge), registered));
    }

    @Test
    void invalid_conditionExpr_throws_INVALID_CONDITION() {
        // SpEL 파싱 실패 — DAG_INVALID_CONDITION 위반
        List<LineStation> nodes = List.of(node("n-a", "A"), node("n-b", "B"));
        LineTrack edge = new LineTrack("e1", "def-test", "n-a", "n-b",
                "#result[ == ",  // 잘못된 SpEL 문법
                "N", null, null, null, null);
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(nodes, List.of(edge), registered));
        assertTrue(ex.getMessage().contains(ErrorCodes.DAG_INVALID_CONDITION), ex.getMessage());
        assertTrue(ex.getMessage().contains("e1"), ex.getMessage());
    }

    @Test
    void blank_conditionExpr_doesNotTriggerValidation() {
        // 조건 없음 (null/blank) — 검증 패스
        List<LineStation> nodes = List.of(node("n-a", "A"), node("n-b", "B"));
        LineTrack edgeNull = new LineTrack("e1", "def-test", "n-a", "n-b", null,
                "N", null, null, null, null);
        LineTrack edgeBlank = new LineTrack("e2", "def-test", "n-a", "n-b", "   ",
                "N", null, null, null, null);
        assertDoesNotThrow(() -> validator.validate(nodes, List.of(edgeNull), registered));
        // edgeBlank는 별개 검증 (같은 from→to 두 번 — DagValidator가 이걸 거부 안 하므로 OK)
        assertDoesNotThrow(() -> validator.validate(nodes,
                List.of(new LineTrack("e3", "def-test", "n-a", "n-b", "   ",
                        "N", null, null, null, null)), registered));
    }

    @Test
    void multiple_violations_combined_in_one_exception() {
        // 자기-참조 + 미등록 액티비티 + dangling
        List<LineStation> nodes = List.of(node("n-a", "UNKNOWN1"), node("n-b", "B"));
        List<LineTrack> edges = List.of(
                edge("e1", "n-a", "n-a"),         // self-loop
                edge("e2", "n-b", "n-ghost")      // dangling
        );
        LineEngineException ex = assertThrows(LineEngineException.class,
                () -> validator.validate(nodes, edges, registered));
        // 메시지에 여러 위반 코드가 모두 포함됨
        String msg = ex.getMessage();
        assertTrue(msg.contains(ErrorCodes.DAG_SELF_LOOP), msg);
        assertTrue(msg.contains(ErrorCodes.DAG_UNKNOWN_ACTIVITY), msg);
        assertTrue(msg.contains(ErrorCodes.DAG_DANGLING_EDGE), msg);
    }

    private LineStation node(String id, String activityNm) {
        return new LineStation(id, "def-test", id + "-label", activityNm,
                null, null, null, null, "N", null, null, null, null);
    }

    private LineTrack edge(String id, String from, String to) {
        return new LineTrack(id, "def-test", from, to, null,
                "N", null, null, null, null);
    }

    /** 테스트 케이스 빌더 */
    private static class WrongCase {
        record NodeEdge(List<LineStation> nodes, List<LineTrack> edges) {}

        static List<NodeEdge> cycle() {
            // A → B, B → A
            LineStation na = new LineStation("n-a", "def-test", "A-lbl", "A",
                    null, null, null, null, "N", null, null, null, null);
            LineStation nb = new LineStation("n-b", "def-test", "B-lbl", "B",
                    null, null, null, null, "N", null, null, null, null);
            LineTrack e1 = new LineTrack("e1", "def-test", "n-a", "n-b", null,
                    "N", null, null, null, null);
            LineTrack e2 = new LineTrack("e2", "def-test", "n-b", "n-a", null,
                    "N", null, null, null, null);
            return List.of(new NodeEdge(List.of(na, nb), List.of(e1, e2)));
        }
    }
}
