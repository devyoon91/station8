package com.station8.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 본인 비밀번호 변경 (#121 DS5) — 로그인 사용자 전용.
 *
 * <p>경로: {@code /me/password} (인증 필요, ADMIN 무관).</p>
 */
@Controller
@RequestMapping("/me/password")
public class PasswordChangeController {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeController.class);

    private final LineUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public PasswordChangeController(LineUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public String form(Model model, Authentication authentication) {
        model.addAttribute("username", authentication.getName());
        return "me-password";
    }

    @PostMapping
    public String change(@RequestParam("currentPassword") String currentPassword,
                         @RequestParam("newPassword") String newPassword,
                         @RequestParam("confirmPassword") String confirmPassword,
                         Authentication authentication, RedirectAttributes flash) {
        try {
            LineUser user = repository.findByUsername(authentication.getName());
            if (user == null) throw new IllegalStateException("로그인 사용자 정보를 찾을 수 없습니다");
            if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
                throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다");
            }
            if (!newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("새 비밀번호와 확인이 일치하지 않습니다");
            }
            String policyError = PasswordPolicy.validate(newPassword);
            if (policyError != null) throw new IllegalArgumentException(policyError);

            repository.updatePasswordHash(user.id(), passwordEncoder.encode(newPassword),
                    user.username());
            log.info("Password changed by self: {}", user.username());
            flash.addFlashAttribute("pwMsg", "[OK] 비밀번호가 변경되었습니다");
            flash.addFlashAttribute("pwOk", true);
        } catch (Exception ex) {
            flash.addFlashAttribute("pwMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("pwOk", false);
        }
        return "redirect:/me/password";
    }
}
