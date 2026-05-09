package com.station8.app.security;

/**
 * 비밀번호 정책 (#121 DS1) — 최소 8자 + 숫자 1+ + 특수문자 1+.
 *
 * <p>대문자/소문자 강제는 안 함 — 8자+ 숫자+특수만 통과하면 OK.
 * 정책 강화는 별개 후속 (DS5 reset 흐름과 같이 검토).</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    private PasswordPolicy() {}

    /**
     * 정책 위반 시 메시지 반환, 통과면 null.
     */
    public static String validate(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH) {
            return "비밀번호는 최소 " + MIN_LENGTH + "자 이상이어야 합니다";
        }
        if (!raw.chars().anyMatch(Character::isDigit)) {
            return "비밀번호는 숫자를 1자 이상 포함해야 합니다";
        }
        if (raw.chars().allMatch(Character::isLetterOrDigit)) {
            return "비밀번호는 특수문자(!@#$%^&* 등)를 1자 이상 포함해야 합니다";
        }
        return null;
    }
}
