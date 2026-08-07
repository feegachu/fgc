package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.mapper.AuthUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// FUN-001 개발 순서 6

/**
 * app_user / app_role 로 로그인 사용자를 조회한다(FUN-001).
 *
 * account_status 는 화면정의서 AUTH-W01 "잠긴 계정(LOCKED)의 로그인 → 거절합니다"를 따른다.
 * 다만 거절 사유는 화면에 구분해 내보내지 않는다 — SecurityConfig 가 실패를 /login?error 하나로 모은다.
 */
@Service
public class FgcUserDetailsService implements UserDetailsService {

    private static final String STATUS_LOCKED = "LOCKED";
    private static final String STATUS_DISABLED = "DISABLED";

    private final AuthUserMapper authUserMapper;

    public FgcUserDetailsService(AuthUserMapper authUserMapper) {
        this.authUserMapper = authUserMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String loginId) {
        AppUserView user = authUserMapper.findByLoginId(loginId);

        if (user == null) {
            // DaoAuthenticationProvider 가 이 예외를 BadCredentialsException 으로 감춘다(기본값).
            throw new UsernameNotFoundException("등록되지 않은 로그인 아이디");
        }

        boolean enabled = !STATUS_DISABLED.equals(user.getAccountStatus());
        boolean accountNonLocked = !STATUS_LOCKED.equals(user.getAccountStatus());

        return new FgcUserDetails(user, enabled, accountNonLocked);
    }
}
