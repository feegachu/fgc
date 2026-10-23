package com.susukkang.fgc.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// FUN-001 개발 순서 2

/**
 * 설명 : 로그인 인증에 필요한 값만 추린 app_user + app_role 조회 DTO.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUserView {
    private Long userId;
    private String loginId;
    private String passwordHash;
    private String userName;
    private String roleCode;
    private String accountStatus;
    private Long agentId;

    /** 기존 내부 사용자 fixture와의 생성자 호환. 설계사 인증 조회는 agentId까지 전달한다. */
    public AppUserView(Long userId, String loginId, String passwordHash, String userName,
                       String roleCode, String accountStatus) {
        this(userId, loginId, passwordHash, userName, roleCode, accountStatus, null);
    }
}
