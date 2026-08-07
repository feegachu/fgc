package com.susukkang.fgc.auth.dto;

import lombok.Getter;
import lombok.Setter;

// FUN-001 개발 순서 2

/**
 * 로그인 인증에 필요한 값만 추린 app_user + app_role projection. AuthUserMapper 조회 결과
 */
@Getter
@Setter
public class AppUserView {
    private Long userId;
    private String loginId;
    private String passwordHash;
    private String userName;
    private String roleCode;
    private String accountStatus;
}
