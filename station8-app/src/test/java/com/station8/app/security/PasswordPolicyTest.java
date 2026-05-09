package com.station8.app.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void rejectsShortPassword() {
        assertThat(PasswordPolicy.validate("a1!")).contains("8자 이상");
    }

    @Test
    void rejectsNoDigit() {
        assertThat(PasswordPolicy.validate("password!")).contains("숫자");
    }

    @Test
    void rejectsNoSpecialChar() {
        assertThat(PasswordPolicy.validate("password1")).contains("특수문자");
    }

    @Test
    void acceptsValidPassword() {
        assertThat(PasswordPolicy.validate("Hello!1234")).isNull();
        assertThat(PasswordPolicy.validate("a1!aaaaa")).isNull();
    }

    @Test
    void rejectsNull() {
        assertThat(PasswordPolicy.validate(null)).contains("8자 이상");
    }
}
