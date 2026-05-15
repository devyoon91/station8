package com.station8.app.security;

import com.station8.app.util.Dates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 어드민 — 사용자 관리 (#121 DS3): ADMIN만 신규 사용자 추가.
 *
 * <p>경로: 모두 {@code /admin/users/**} → SecurityConfig에서 ADMIN 역할 필요.</p>
 */
@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final LineUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(LineUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public String list(Model model, Authentication authentication) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LineUser u : repository.findAll()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", u.id());
            row.put("username", u.username());
            row.put("displayNm", u.displayNm() == null ? "" : u.displayNm());
            row.put("enabled", "Y".equals(u.enabledFl()));
            row.put("isAdmin", u.roles().contains("ADMIN"));
            row.put("rolesText", String.join(", ", u.roles()));
            row.put("regDt", Dates.format(u.regDt()));
            row.put("isSelf", authentication != null && authentication.getName().equals(u.username()));
            rows.add(row);
        }
        model.addAttribute("users", rows);
        model.addAttribute("totalCount", rows.size());
        model.addAttribute("navAdminUsers", true);
        return "admin-users";
    }

    @PostMapping
    public String create(@RequestParam("username") String username,
                         @RequestParam(value = "displayNm", required = false) String displayNm,
                         @RequestParam("password") String password,
                         @RequestParam(value = "isAdmin", required = false) String isAdmin,
                         RedirectAttributes flash, Authentication authentication) {
        try {
            if (username == null || username.isBlank()
                    || !username.matches("[A-Za-z][A-Za-z0-9_.-]{1,63}")) {
                throw new IllegalArgumentException(
                        "사용자명은 영문자로 시작 + 영숫자/_/-/. 만 허용 (2~64자)");
            }
            if (repository.findByUsername(username) != null) {
                throw new IllegalArgumentException("이미 존재하는 사용자명: " + username);
            }
            String policyError = PasswordPolicy.validate(password);
            if (policyError != null) {
                throw new IllegalArgumentException(policyError);
            }
            Set<String> roles = new HashSet<>();
            roles.add("USER");
            if (isAdmin != null) roles.add("ADMIN");

            LineUser u = new LineUser(
                    UUID.randomUUID().toString(),
                    username,
                    passwordEncoder.encode(password),
                    displayNm,
                    "Y", roles,
                    "N", null,
                    authentication != null ? authentication.getName() : "system",
                    null, null);
            repository.insert(u);
            log.info("User created: username={}, roles={} (by {})",
                    username, roles, authentication != null ? authentication.getName() : "system");
            flash.addFlashAttribute("userMsg", "[OK] '" + username + "' 사용자 생성 완료");
            flash.addFlashAttribute("userOk", true);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("userMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("userOk", false);
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable("id") String userId,
                                @RequestParam("newPassword") String newPassword,
                                RedirectAttributes flash, Authentication authentication) {
        try {
            LineUser u = repository.findById(userId);
            if (u == null) throw new IllegalArgumentException("사용자를 찾을 수 없음");
            String policyError = PasswordPolicy.validate(newPassword);
            if (policyError != null) throw new IllegalArgumentException(policyError);

            repository.updatePasswordHash(userId, passwordEncoder.encode(newPassword),
                    authentication != null ? authentication.getName() : "system");
            log.info("Password reset by ADMIN for user {}", u.username());
            flash.addFlashAttribute("userMsg", "[OK] '" + u.username() + "' 비밀번호 재설정 완료");
            flash.addFlashAttribute("userOk", true);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("userMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("userOk", false);
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/toggle-enabled")
    public String toggleEnabled(@PathVariable("id") String userId,
                                RedirectAttributes flash, Authentication authentication) {
        try {
            LineUser u = repository.findById(userId);
            if (u == null) throw new IllegalArgumentException("사용자를 찾을 수 없음");
            // 자신을 disable 못 하게
            if (authentication != null && authentication.getName().equals(u.username())) {
                throw new IllegalArgumentException("본인 계정은 비활성화할 수 없습니다");
            }
            boolean newEnabled = !"Y".equals(u.enabledFl());
            repository.setEnabled(userId, newEnabled,
                    authentication != null ? authentication.getName() : "system");
            flash.addFlashAttribute("userMsg",
                    "[OK] '" + u.username() + "' " + (newEnabled ? "활성화" : "비활성화"));
            flash.addFlashAttribute("userOk", true);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("userMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("userOk", false);
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") String userId,
                         RedirectAttributes flash, Authentication authentication) {
        try {
            LineUser u = repository.findById(userId);
            if (u == null) throw new IllegalArgumentException("사용자를 찾을 수 없음");
            if (authentication != null && authentication.getName().equals(u.username())) {
                throw new IllegalArgumentException("본인 계정은 삭제할 수 없습니다");
            }
            repository.softDelete(userId,
                    authentication != null ? authentication.getName() : "system");
            log.info("User soft-deleted: {} (by {})", u.username(),
                    authentication != null ? authentication.getName() : "system");
            flash.addFlashAttribute("userMsg", "[OK] '" + u.username() + "' 삭제 완료");
            flash.addFlashAttribute("userOk", true);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("userMsg", "[FAIL] " + ex.getMessage());
            flash.addFlashAttribute("userOk", false);
        }
        return "redirect:/admin/users";
    }
}
