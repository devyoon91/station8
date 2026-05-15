package com.station8.app.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.view.RedirectView;

/**
 * MVC view 컨트롤러 (즉, {@code @RestController}가 아닌 {@code @Controller}) 의 uncaught
 * 예외를 한 곳에서 캐치 + 로그한다.
 *
 * <p>{@link GlobalRestExceptionHandler}는 {@code @RestControllerAdvice} 라 REST endpoint 만
 * 매칭. MVC 컨트롤러의 예외는 Spring 기본 핸들러로 떨어져 stack trace 가 묻히거나, view 렌더
 * 도중 발생하면 응답이 chunked encoding 중간에 끊기는 (브라우저 {@code ERR_INCOMPLETE_CHUNKED_ENCODING})
 * 케이스로 흘러간다.</p>
 *
 * <h3>본 advice의 책임</h3>
 * <ul>
 *   <li>MVC handler 메서드에서 던진 예외를 캐치 + 전체 stack trace 로그</li>
 *   <li>{@link ResponseStatusException}은 그대로 전파 (Spring이 처리)</li>
 *   <li>그 외 {@link Exception}은 500 + 에러 페이지로 격하</li>
 * </ul>
 *
 * <p>주의: view 렌더링 도중 (즉 controller가 view name 반환 후) 발생하는 예외는 본 advice가
 * 잡지 못한다. 그건 Spring DispatcherServlet이 직접 처리하나, 응답이 이미 commit된 상태라
 * 깔끔한 fallback이 불가능하다 — 그 경우라도 stack trace는 servlet 컨테이너 (Tomcat) 로그에
 * 찍히므로 docker logs로 확인 가능.</p>
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
@Order(0)  // GlobalRestExceptionHandler보다 먼저 매칭되지 않도록 — REST 는 @RestControllerAdvice가 cover
public class GlobalMvcExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalMvcExceptionHandler.class);

    /**
     * 명시적으로 던진 ResponseStatusException은 그대로 다시 던져 Spring이 status code + reason을
     * 쓸 수 있게 한다 (e.g. 404 not-found from {@link LineMonitoringController#timeline}).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public void rethrowStatusException(ResponseStatusException ex) {
        // 본 advice는 처리 안 함 — Spring의 ResponseStatusExceptionResolver가 처리
        throw ex;
    }

    /**
     * 그 외 모든 uncaught 예외 — 500 + 로그 + 표준 에러 페이지.
     * stack trace는 반드시 로그로 출력 (브라우저는 ERR_INCOMPLETE_CHUNKED_ENCODING만 보여서
     * 운영자가 원인 파악 불가능했음).
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public RedirectView handleUncaught(Exception ex, HttpServletRequest request) {
        log.error("Uncaught MVC exception — path={}, query={}",
                request.getRequestURI(), request.getQueryString(), ex);
        // 응답이 이미 commit된 상태면 redirect 자체가 무시됨 — 그래도 로그는 남음 (목적 달성).
        return new RedirectView("/error?from=" + request.getRequestURI(), false);
    }
}
