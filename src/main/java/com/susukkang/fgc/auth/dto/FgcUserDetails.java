package com.susukkang.fgc.auth.dto;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

// FUN-001 개발 순서 3

/**
 * 인증 주체에 app_user.user_id 와 표시용 이름을 얹은 UserDetails.
 *
 * 스키마 전반의 created_by / approved_by / posted_by 등이 app_user(user_id)를 참조한다.
 * principal 이 user_id 를 들고 다녀야 각 도메인이 @AuthenticationPrincipal 로 바로 꺼내 쓴다.
 */
public class FgcUserDetails extends User {

    private static final String ROLE_PREFIX = "ROLE_";

    private final Long userId;
    private final String userName;
    private final String roleCode;

    public FgcUserDetails(AppUserView user, boolean enabled, boolean accountNonLocked) {
        super(
                user.getLoginId(),
                user.getPasswordHash(),
                enabled,
                true,
                true,
                accountNonLocked,
                authorities(user.getRoleCode())
        );
        this.userId = user.getUserId();
        this.userName = user.getUserName();
        this.roleCode = user.getRoleCode();
    }

    private static List<GrantedAuthority> authorities(String roleCode) {
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + roleCode));
    }

    public Long getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public String getRoleCode() {
        return roleCode;
    }
}
