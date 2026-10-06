package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.springframework.security.core.AuthenticationException;

/**
 * 설명 : Access 토큰은 서명·만료가 유효하지만 그 로그인(sid)이 이미 무효화된 경우(#400).
 * 중복 로그인으로 밀려났으면 FGC-AUTH-004, 로그아웃·재사용 탐지면 FGC-AUTH-002 로 응답한다.
 */
public class JwtSessionRevokedException extends AuthenticationException {

    private final transient FgcErrorCode errorCode;

    public JwtSessionRevokedException(FgcErrorCode errorCode) {
        super(errorCode.getCode());
        this.errorCode = errorCode;
    }

    public FgcErrorCode getErrorCode() {
        return errorCode;
    }
}
