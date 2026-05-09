package com.station8.app.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 Mustache view에 현재 사용자 정보 + CSRF 토큰을 자동 주입 (#121).
 *
 * <p>Spring Security CsrfFilter가 request attribute에 {@code CsrfToken}을 저장하지만,
 * Spring Boot Mustache view는 request attribute를 자동 모델 노출하지 않는다 — 명시적 주입.</p>
 */
@ControllerAdvice
public class CurrentUserModelAdvice {

    @ModelAttribute("currentUser")
    public CurrentUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return new CurrentUser(false, null, false);
        }
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return new CurrentUser(true, auth.getName(), isAdmin);
    }

    @ModelAttribute("_csrf")
    public CsrfToken csrfToken(HttpServletRequest request) {
        return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    }

    public record CurrentUser(boolean authenticated, String username, boolean admin) {}
}
