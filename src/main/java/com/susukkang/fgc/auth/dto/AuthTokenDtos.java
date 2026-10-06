package com.susukkang.fgc.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 설명 : 2차 JWT 토큰 API(#400) 요청·응답. 필드명은 첫 소비자(#402 client.ts·#404 로그인)와 맞춘다.
 */
public final class AuthTokenDtos {

    private AuthTokenDtos() {
    }

    public record LoginRequest(@NotBlank String loginId, @NotBlank String password) {
    }

    /** Refresh 는 본문에 싣지 않는다 — HttpOnly 쿠키로만 준다(인터페이스정의서 §2-1-1). */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    /** 1차 ShellAdvice 가 화면 모델에 넣던 값을 대신한다. 플래그는 @PreAuthorize 와 같은 식으로 판정한다. */
    public record MeResponse(String loginId, String userName, String roleCode,
                             boolean canProcess, boolean canViewAuditLog, boolean canHandleException,
                             boolean canReverseJournal, boolean canFinalizeValidation) {
    }
}
