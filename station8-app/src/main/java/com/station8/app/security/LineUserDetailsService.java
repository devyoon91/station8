package com.station8.app.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring Security {@link UserDetailsService} 구현 — {@link LineUserRepository}로부터
 * 사용자/역할을 조회하여 표준 {@link UserDetails}로 변환.
 *
 * <p>{@code ENABLED_FL='N'}이면 disabled 사용자로 마킹 — 로그인 시 거부.</p>
 */
@Service
public class LineUserDetailsService implements UserDetailsService {

    private final LineUserRepository repository;

    public LineUserDetailsService(LineUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LineUser u = repository.findByUsername(username);
        if (u == null) {
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username);
        }
        List<SimpleGrantedAuthority> authorities = u.roles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return User.builder()
                .username(u.username())
                .password(u.passwordHash())
                .disabled(!"Y".equals(u.enabledFl()))
                .authorities(authorities)
                .build();
    }
}
