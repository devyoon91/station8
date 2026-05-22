package com.station8.engine.test;

import org.junit.jupiter.api.Test;

import static com.station8.engine.test.ActivityOutputAssertions.assertActivityOutput;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityOutputAssertionsTest {

    @Test
    void isEqualTo_passWhenSame() {
        assertActivityOutput("{\"a\":1}").isEqualTo("{\"a\":1}");
    }

    @Test
    void isEqualTo_failWhenDifferent() {
        assertThrows(AssertionError.class,
                () -> assertActivityOutput("{\"a\":1}").isEqualTo("{\"a\":2}"));
    }

    @Test
    void contains_passWhenSubstringPresent() {
        assertActivityOutput("{\"status\":\"OK\",\"orderId\":\"123\"}").contains("OK");
    }

    @Test
    void contains_failWhenMissing() {
        assertThrows(AssertionError.class,
                () -> assertActivityOutput("{\"status\":\"OK\"}").contains("FAIL"));
    }

    @Test
    void hasField_passForFlatJson() {
        assertActivityOutput("{\"orderId\":\"123\",\"status\":\"OK\"}")
                .hasField("orderId")
                .hasField("status");
    }

    @Test
    void hasField_failWhenMissing() {
        assertThrows(AssertionError.class,
                () -> assertActivityOutput("{\"orderId\":\"123\"}").hasField("status"));
    }

    @Test
    void hasField_falsePositiveAvoidedForValueOnlyMatch() {
        // 값 안에 "status"가 들어있어도 키로는 안 잡혀야 함
        assertThrows(AssertionError.class,
                () -> assertActivityOutput("{\"msg\":\"the status was OK\"}").hasField("status"));
    }

    @Test
    void doesNotContainField_passWhenAbsent() {
        assertActivityOutput("{\"orderId\":\"123\"}").doesNotContainField("error");
    }

    @Test
    void doesNotContainField_failWhenPresent() {
        assertThrows(AssertionError.class,
                () -> assertActivityOutput("{\"error\":\"x\"}").doesNotContainField("error"));
    }

    @Test
    void hasFieldValue_passForStringValue() {
        assertActivityOutput("{\"status\":\"OK\",\"orderId\":\"123\"}")
                .hasFieldValue("status", "OK")
                .hasFieldValue("orderId", "123");
    }

    @Test
    void hasFieldValue_failWhenValueDiffers() {
        assertThrows(AssertionError.class,
                () -> assertActivityOutput("{\"status\":\"OK\"}").hasFieldValue("status", "FAIL"));
    }

    @Test
    void hasFieldValue_handlesWhitespaceAroundColon() {
        assertActivityOutput("{\"status\"  :  \"OK\"}").hasFieldValue("status", "OK");
    }

    @Test
    void matches_regexSearch() {
        assertActivityOutput("processed 42 rows").matches("processed \\d+ rows");
    }

    @Test
    void chain_combinesMultipleAssertions() {
        assertActivityOutput("{\"orderId\":\"123\",\"status\":\"OK\"}")
                .hasField("orderId")
                .hasFieldValue("status", "OK")
                .doesNotContainField("error")
                .contains("123");
    }

    @Test
    void raw_returnsOriginal() {
        String out = "{\"a\":1}";
        org.junit.jupiter.api.Assertions.assertEquals(out, assertActivityOutput(out).raw());
    }
}
