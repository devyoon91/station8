package com.station8.app.config;

import com.samskivert.mustache.Mustache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JMustache 1.16의 Map 처리 결함 회피.
 *
 * <p>JMustache의 {@code MAP_FETCHER}는 {@code Map<K,V>}에 key가 존재해도 value가 {@code null}이면
 * {@code Template.NO_FETCHER_FOUND}로 격하한다. 그 결과 컨트롤러가
 * {@code map.put("nodeId", null)}로 명시적으로 키를 박아도 Mustache는 "No key, method or field with name 'nodeId'"
 * {@link com.samskivert.mustache.MustacheException.Context} 를 던진다.</p>
 *
 * <p>실증된 시나리오: Dashboard Details({@code /line/instance/{id}}) 페이지에서 활동 row의 {@code nodeId}가
 * {@code null} 인 경우 (legacy/linear mode 활동 또는 #277-followup nodeId 손실 케이스) → view 렌더 도중
 * 예외 → 응답이 chunked 인코딩 중간에 끊겨 브라우저 {@code ERR_INCOMPLETE_CHUNKED_ENCODING}.</p>
 *
 * <p>본 config는 Spring Boot의 {@link org.springframework.boot.autoconfigure.mustache.MustacheAutoConfiguration}
 * 가 만든 default {@code Mustache.Compiler} bean을 override해 {@code nullValue("")} +
 * {@code defaultValue("")}를 강제한다 — null 값과 missing key 모두 빈 문자열로 렌더.</p>
 *
 * <h3>왜 컨트롤러에서 null-coalescing하지 않나</h3>
 * 모든 컨트롤러의 모든 모델 키마다 {@code val == null ? "" : val} 분기를 두는 건
 * 100+ 곳 변경 + 회귀 가드 어려움. Mustache 레벨에서 한 번 처리하는 게 안전 + 유지보수 용이.
 */
@Configuration
public class MustacheConfig {

    @Bean
    public Mustache.Compiler mustacheCompiler(Mustache.TemplateLoader templateLoader) {
        return Mustache.compiler()
                .withLoader(templateLoader)
                .nullValue("")        // null Map values → 빈 문자열 (JMustache 1.16 결함 회피)
                .defaultValue("");    // missing keys → 빈 문자열 (방어적 default)
    }
}
