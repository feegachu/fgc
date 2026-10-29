package com.susukkang.fgc.common.security;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.Arrays;

/**
 * 설명 : 인증된 SecurityContext 를 담은 세션 — 1차 폼 로그인이 남기던 것과 같은 모양이다.
 * 임의 JSESSIONID 문자열은 서버에 대응되는 세션이 없어 세션을 읽는 설정으로 되돌아가도 401 이라 회귀를 잡지 못한다(PR #734 리뷰).
 * 이 세션으로 요청해도 401 이어야 STATELESS(#419)가 지켜진다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-28
 */
public final class AuthenticatedSessions {

    private AuthenticatedSessions() {
    }

    public static MockHttpSession of(Authentication authentication) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        return session;
    }

    public static MockHttpSession withRoles(String... roles) {
        String[] authorities = Arrays.stream(roles).map(role -> "ROLE_" + role).toArray(String[]::new);
        return of(UsernamePasswordAuthenticationToken.authenticated("session-user", null,
                AuthorityUtils.createAuthorityList(authorities)));
    }
}
