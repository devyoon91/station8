package com.station8.engine.test;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 플러그인 액티비티 출력(보통 JSON 문자열)에 대한 간단한 단언 helper (#319).
 *
 * <p>본 클래스는 의도적으로 JSON 파싱 라이브러리를 의존하지 않는다 — 플러그인 테스트의 가벼움이 목표.
 * 평탄한 JSON 객체에 대한 키/값 존재 검사가 주 용도. 복잡한 nested 구조나 array 단언이 필요하면
 * Jackson/JSONassert 등을 별도로 testImplementation으로 잡는 게 권장.</p>
 *
 * <h3>예시</h3>
 * <pre>{@code
 *   String out = plugin.run("{}");
 *
 *   assertActivityOutput(out)
 *       .hasField("orderId")
 *       .hasFieldValue("status", "OK")
 *       .doesNotContainField("error");
 * }</pre>
 *
 * <p>{@link AssertionError} 를 던지므로 JUnit / TestNG 등 일반 테스트 프레임워크와 호환.</p>
 */
public final class ActivityOutputAssertions {

    private ActivityOutputAssertions() {
        // util
    }

    /**
     * 단언 chain의 시작점. 액티비티 출력 문자열을 받아 단언 객체를 돌려준다.
     *
     * @param output 액티비티 메서드의 반환 문자열 (보통 JSON)
     * @return 단언 객체
     */
    public static ActivityOutputAssert assertActivityOutput(String output) {
        return new ActivityOutputAssert(output);
    }

    /**
     * 단일 출력에 대한 chained 단언 객체.
     */
    public static final class ActivityOutputAssert {

        private final String output;

        ActivityOutputAssert(String output) {
            this.output = Objects.requireNonNull(output, "output은 null이 아니어야 한다");
        }

        /**
         * 출력이 정확히 expected와 같다고 단언.
         *
         * @param expected 기대 문자열
         * @return 본 단언 객체
         */
        public ActivityOutputAssert isEqualTo(String expected) {
            if (!Objects.equals(output, expected)) {
                throw new AssertionError("출력이 일치하지 않음.\n  expected: " + expected + "\n  actual:   " + output);
            }
            return this;
        }

        /**
         * 출력에 substring이 포함되어 있다고 단언.
         *
         * @param substring 포함되어야 할 부분 문자열
         * @return 본 단언 객체
         */
        public ActivityOutputAssert contains(String substring) {
            if (!output.contains(substring)) {
                throw new AssertionError("출력에 substring이 없음.\n  substring: " + substring + "\n  actual:    " + output);
            }
            return this;
        }

        /**
         * 출력이 regex와 매치된다고 단언.
         *
         * @param regex Java 정규식
         * @return 본 단언 객체
         */
        public ActivityOutputAssert matches(String regex) {
            if (!Pattern.compile(regex, Pattern.DOTALL).matcher(output).find()) {
                throw new AssertionError("출력이 regex와 매치하지 않음.\n  regex:  " + regex + "\n  actual: " + output);
            }
            return this;
        }

        /**
         * 평탄한 JSON 객체에서 키 {@code "name":} 패턴이 있다고 단언. nested 객체 안은 검사 안 함.
         *
         * @param fieldName JSON 키
         * @return 본 단언 객체
         */
        public ActivityOutputAssert hasField(String fieldName) {
            String quotedKey = "\"" + Pattern.quote(fieldName) + "\"\\s*:";
            if (!Pattern.compile(quotedKey).matcher(output).find()) {
                throw new AssertionError("출력에 JSON 필드가 없음.\n  field:  " + fieldName + "\n  actual: " + output);
            }
            return this;
        }

        /**
         * 평탄한 JSON 객체에서 키 {@code "name":} 패턴이 없다고 단언.
         *
         * @param fieldName JSON 키
         * @return 본 단언 객체
         */
        public ActivityOutputAssert doesNotContainField(String fieldName) {
            String quotedKey = "\"" + Pattern.quote(fieldName) + "\"\\s*:";
            if (Pattern.compile(quotedKey).matcher(output).find()) {
                throw new AssertionError("출력에 JSON 필드가 있어서는 안 됨.\n  field:  " + fieldName + "\n  actual: " + output);
            }
            return this;
        }

        /**
         * 평탄한 JSON에서 {@code "name":"value"} 패턴이 있다고 단언. 숫자/불리언이 아닌 문자열 값만 다룬다 —
         * 더 복잡한 비교는 별도 JSON 라이브러리를 사용.
         *
         * @param fieldName JSON 키
         * @param value 기대하는 문자열 값
         * @return 본 단언 객체
         */
        public ActivityOutputAssert hasFieldValue(String fieldName, String value) {
            String pattern = "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"" + Pattern.quote(value) + "\"";
            if (!Pattern.compile(pattern).matcher(output).find()) {
                throw new AssertionError(
                        "출력의 JSON 필드값이 기대와 다름.\n  field:    " + fieldName
                                + "\n  expected: \"" + value + "\"\n  actual:   " + output);
            }
            return this;
        }

        /**
         * 원본 출력 문자열 반환 — 추가 검사가 필요하면 사용.
         *
         * @return 원본 출력
         */
        public String raw() {
            return output;
        }
    }
}
