package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.entity.AuthRefreshToken;
import com.susukkang.fgc.auth.entity.AuthRefreshToken.RevokedReason;
import com.susukkang.fgc.auth.repository.AuthRefreshTokenRepository;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 설명 : Bearer 요청의 principal 을 세션 로그인과 같은 {@link FgcUserDetails} 로 만든다(#400).
 *
 * 그래서 컨트롤러의 FgcUserDetails 캐스팅과 @PreAuthorize 는 인증 방식이 바뀌어도 그대로다.
 * 역할은 토큰의 roles 클레임이 아니라 요청마다 DB 에서 다시 읽는다 — 클레임은 화면 표시용이고,
 * 역할을 회수하면 다음 요청부터 바로 반영돼야 한다(인터페이스정의서 §2-1-1 "역할 정보").
 *
 * sid 가 가리키는 로그인의 최신 Refresh 행이 무효화됐으면 Access 만료(30분)를 기다리지 않고 거절한다.
 *
 * Converter 인터페이스를 구현하지 않는 이유 — @WebMvcTest 슬라이스는 Converter 빈을 자동으로 스캔한다.
 * 구현하면 SecurityConfig 를 import 하는 슬라이스 테스트 전부가 FgcUserDetailsService 를 요구하며 깨진다.
 * SecurityConfig 가 람다(Converter&lt;Jwt, AbstractAuthenticationToken&gt;)로 감싸 등록한다.
 */
@Component
public class FgcJwtAuthenticationConverter {

    public static final String CLAIM_SID = "sid";
    public static final String CLAIM_ROLES = "roles";

    private final FgcUserDetailsService userDetailsService;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final AccountStatusUserDetailsChecker accountStatusChecker = new AccountStatusUserDetailsChecker();

    public FgcJwtAuthenticationConverter(FgcUserDetailsService userDetailsService,
                                         AuthRefreshTokenRepository refreshTokenRepository) {
        this.userDetailsService = userDetailsService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // ponytail: 요청마다 사용자 1회 + 세션 1회 DB 조회(ix_auth_refresh_token_session 인덱스).
    //           병목이면 짧은 TTL 캐시를 붙이되, 그 TTL 만큼 역할 회수·중복 로그인 차단이 늦어진다.
    public AbstractAuthenticationToken convert(Jwt jwt) {
        FgcUserDetails user = (FgcUserDetails) userDetailsService.loadUserByUsername(jwt.getSubject());
        accountStatusChecker.check(user);
        requireActiveSession(jwt, user.getUserId());
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    private void requireActiveSession(Jwt jwt, Long userId) {
        AuthRefreshToken latest = refreshTokenRepository
                .findFirstBySessionIdOrderByRefreshTokenIdDesc(sessionId(jwt))
                .filter(token -> token.getUserId().equals(userId))
                .orElseThrow(() -> new JwtSessionRevokedException(FgcErrorCode.AUTH_002));
        if (latest.isActive()) {
            return;
        }
        throw new JwtSessionRevokedException(latest.getRevokedReason() == RevokedReason.SUPERSEDED
                ? FgcErrorCode.AUTH_004
                : FgcErrorCode.AUTH_002);
    }

    private static UUID sessionId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getClaimAsString(CLAIM_SID));
        } catch (RuntimeException e) {
            throw new JwtSessionRevokedException(FgcErrorCode.AUTH_002);
        }
    }
}
