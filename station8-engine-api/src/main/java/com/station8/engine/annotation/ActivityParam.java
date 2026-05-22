package com.station8.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 활동 입력 파라미터 메타. M21 (#304) — 빌더가 이걸 보고 자동 form을 렌더한다.
 *
 * <p>{@link Activity#params()} 배열에 박는다 — 그 활동의 {@code inputParams} JSON object가 가질
 * 키들을 선언적으로 명시. 빌더는 form 필드, 카탈로그 API는 schema 응답에 활용.</p>
 *
 * <p>{@code params()}가 비어있으면 빌더는 기존 free-form textarea를 그대로 유지 (점진 도입).</p>
 *
 * <h3>예시</h3>
 * <pre>{@code
 *   @Activity(value = "http.request", params = {
 *       @ActivityParam(name = "method", kind = SELECT, required = true,
 *                      options = {"GET", "POST", "PUT", "DELETE", "PATCH"}),
 *       @ActivityParam(name = "url", kind = STRING, required = true,
 *                      description = "절대 URL. 표현식 가능"),
 *       @ActivityParam(name = "credentialId", kind = CREDENTIAL,
 *                      options = {"http_bearer", "http_basic", "api_key"})
 *   })
 *   public String request(String input) { ... }
 * }</pre>
 *
 * <p>schema는 활동의 실제 input record와 일치하는 게 source-of-truth — record 필드 변경 시
 * 같이 갱신해야 한다 (자동 sync는 별도 sub-issue).</p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface ActivityParam {

    /** 파라미터 이름 — inputParams JSON object의 키. 필수. */
    String name();

    /** 입력 종류 — 빌더가 어떤 input element를 렌더할지 결정. */
    Kind kind() default Kind.STRING;

    /** required면 빌더가 빈 값 저장 차단 + UI에 * 표시. */
    boolean required() default false;

    /** 빌더 form에 헬프 텍스트로 노출. */
    String description() default "";

    /**
     * {@link Kind#SELECT}이면 dropdown 옵션 목록.
     * {@link Kind#CREDENTIAL}이면 호환 credential type 화이트리스트 (vault dropdown filter).
     * 그 외 kind에선 무시.
     */
    String[] options() default {};

    /** 빈 입력 시 적용할 default. 빌더가 form 초기값으로. */
    String defaultValue() default "";

    /**
     * 입력 종류.
     *
     * <ul>
     *   <li>{@link #STRING} — text input. 표현식({@code }} }})}) 가능</li>
     *   <li>{@link #NUMBER} — number input. 표현식도 가능 (평가 결과가 숫자)</li>
     *   <li>{@link #BOOLEAN} — checkbox</li>
     *   <li>{@link #SELECT} — dropdown. {@code options} 필수</li>
     *   <li>{@link #OBJECT} — 임의 JSON object. textarea fallback (부분 form은 별도)</li>
     *   <li>{@link #CREDENTIAL} — vault 등록 이름. 별도 sub-issue에서 dropdown으로</li>
     * </ul>
     */
    enum Kind {
        STRING, NUMBER, BOOLEAN, SELECT, OBJECT, CREDENTIAL
    }
}
