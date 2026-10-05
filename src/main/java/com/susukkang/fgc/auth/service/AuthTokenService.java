package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.entity.AppUser;
import com.susukkang.fgc.auth.entity.AuthRefreshToken;
import com.susukkang.fgc.auth.entity.AuthRefreshToken.RevokedReason;
import com.susukkang.fgc.auth.repository.AppUserRepository;
import com.susukkang.fgc.auth.repository.AuthRefreshTokenRepository;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 설명 : 2차 JWT 토큰 발급·회전·무효화(#400, 인터페이스정의서 §2-1-1).
 *
 * 동시성 규칙 — 같은 사용자의 Refresh 행을 바꾸는 모든 경로는 먼저 app_user 행을 FOR UPDATE 로 잠근다.
 * 잠금 순서가 하나라 교착이 없고, 로그인 두 건이 동시에 와도 뒤의 것이 앞의 것을 SUPERSEDED 로
 * 무효화해 활성 행이 정확히 1개 남는다(최종 방어선은 V43 uq_auth_refresh_token_active_user).
 *
 * 실패도 커밋해야 하는 경로(재사용 탐지 → 전부 무효화)가 있어 거절은 예외가 아니라 결과로 돌려준다.
 * 트랜잭션 안에서 예외를 던지면 무효화까지 롤백된다.
 */
@Service
public class AuthTokenService {

    public static final String TOKEN_TYPE = "Bearer";

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final AppUserRepository appUserRepository;
    private final FgcUserDetailsService userDetailsService;
    private final AuthAuditListener auditListener;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final AccountStatusUserDetailsChecker accountStatusChecker = new AccountStatusUserDetailsChecker();

    public AuthTokenService(JwtEncoder jwtEncoder,
                            AuthRefreshTokenRepository refreshTokenRepository,
                            AppUserRepository appUserRepository,
                            FgcUserDetailsService userDetailsService,
                            AuthAuditListener auditListener,
                            @Value("${fgc.jwt.access-token-ttl}") Duration accessTokenTtl,
                            @Value("${fgc.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.appUserRepository = appUserRepository;
        this.userDetailsService = userDetailsService;
        this.auditListener = auditListener;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /** 발급 결과. refreshToken 은 원문 — 쿠키로만 내보내고 응답 본문·로그에 싣지 않는다. */
    public record IssuedTokens(String accessToken, long expiresInSeconds,
                               String refreshToken, Duration refreshTokenMaxAge) {
    }

    /** tokens 와 error 중 하나만 있다. */
    public record RefreshResult(IssuedTokens tokens, FgcErrorCode error) {
        static RefreshResult rejected(FgcErrorCode error) {
            return new RefreshResult(null, error);
        }
    }

    /**
     * 비밀번호 검증을 마친 사용자에게 새 로그인(session_id)을 연다. 그 계정의 기존 로그인은 전부 SUPERSEDED.
     */
    @Transactional
    public IssuedTokens login(FgcUserDetails user) {
        OffsetDateTime now = OffsetDateTime.now();
        appUserRepository.lockById(user.getUserId());
        int superseded = refreshTokenRepository.revokeActiveByUserId(
                user.getUserId(), RevokedReason.SUPERSEDED, now);
        if (superseded > 0) {
            auditListener.recordTokenEvent("LOGIN_SUPERSEDED", user.getUserId(), user.getUsername(),
                    "무효화된 이전 로그인 " + superseded + "건");
        }
        IssuedTokens tokens = issue(user, UUID.randomUUID(), now.plus(refreshTokenTtl), now);
        auditListener.recordTokenEvent("LOGIN_SUCCESS", user.getUserId(), user.getUsername(), null);
        return tokens;
    }

    /**
     * Refresh 를 회전한다. 이미 회전된(ROTATED) Refresh 가 다시 오면 탈취로 보고 그 사용자의 Refresh 를 전부 무효화한다.
     *
     * LOGOUT·SUPERSEDED 로 무효화된 Refresh 는 거절만 한다. 중복 로그인으로 밀려난 기기가 자동 갱신을
     * 시도할 때마다 "전부 무효화"를 걸면 새로 로그인한 기기까지 끊긴다(검증 시나리오 9 "B는 정상").
     */
    @Transactional
    public RefreshResult refresh(String rawRefreshToken) {
        String hash = hash(rawRefreshToken);
        Optional<Long> userId = refreshTokenRepository.findUserIdByTokenHash(hash);
        if (userId.isEmpty()) {
            return RefreshResult.rejected(FgcErrorCode.AUTH_002);
        }
        OffsetDateTime now = OffsetDateTime.now();
        appUserRepository.lockById(userId.get());
        AuthRefreshToken token = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        String loginId = loginIdOf(token.getUserId());

        if (!token.isActive()) {
            return rejectRevoked(token, loginId, now);
        }
        if (!token.getExpiresAt().isAfter(now)) {
            return RefreshResult.rejected(FgcErrorCode.AUTH_002);
        }
        FgcUserDetails user;
        try {
            user = (FgcUserDetails) userDetailsService.loadUserByUsername(loginId);
            accountStatusChecker.check(user);
        } catch (AuthenticationException e) {
            return RefreshResult.rejected(FgcErrorCode.AUTH_002);
        }

        token.revoke(RevokedReason.ROTATED, now);
        refreshTokenRepository.flush();
        // 로그인 단위의 수명(14일)은 회전해도 늘리지 않는다 — 처음 로그인한 시점부터 센다.
        IssuedTokens tokens = issue(user, token.getSessionId(), token.getExpiresAt(), now);
        auditListener.recordTokenEvent("TOKEN_REFRESH", user.getUserId(), loginId, null);
        return new RefreshResult(tokens, null);
    }

    private RefreshResult rejectRevoked(AuthRefreshToken token, String loginId, OffsetDateTime now) {
        if (token.getRevokedReason() == RevokedReason.SUPERSEDED) {
            return RefreshResult.rejected(FgcErrorCode.AUTH_004);
        }
        if (token.getRevokedReason() == RevokedReason.ROTATED) {
            int revoked = refreshTokenRepository.revokeActiveByUserId(
                    token.getUserId(), RevokedReason.REUSE_DETECTED, now);
            auditListener.recordTokenEvent("REFRESH_REUSE_DETECTED", token.getUserId(), loginId,
                    "회전된 Refresh 재사용 — 활성 로그인 " + revoked + "건 무효화");
        }
        return RefreshResult.rejected(FgcErrorCode.AUTH_002);
    }

    /** 쿠키의 Refresh 가 살아 있으면 그 로그인을 끝낸다. 없거나 이미 무효면 아무것도 하지 않는다(멱등). */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String hash = hash(rawRefreshToken);
        Optional<Long> userId = refreshTokenRepository.findUserIdByTokenHash(hash);
        if (userId.isEmpty()) {
            return;
        }
        appUserRepository.lockById(userId.get());
        AuthRefreshToken token = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        if (!token.isActive()) {
            return;
        }
        token.revoke(RevokedReason.LOGOUT, OffsetDateTime.now());
        auditListener.recordTokenEvent("LOGOUT", token.getUserId(), loginIdOf(token.getUserId()), null);
    }

    private IssuedTokens issue(FgcUserDetails user, UUID sessionId, OffsetDateTime refreshExpiresAt,
                               OffsetDateTime now) {
        String rawRefresh = newRefreshToken();
        refreshTokenRepository.save(AuthRefreshToken.issue(user.getUserId(), sessionId, hash(rawRefresh),
                refreshExpiresAt));

        Instant issuedAt = now.toInstant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(accessTokenTtl))
                .claim(FgcJwtAuthenticationConverter.CLAIM_SID, sessionId.toString())
                // 화면 표시용. 권한 판정은 FgcJwtAuthenticationConverter 가 DB 에서 다시 읽은 역할로 한다.
                .claim(FgcJwtAuthenticationConverter.CLAIM_ROLES, List.of(user.getRoleCode()))
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        return new IssuedTokens(accessToken, accessTokenTtl.toSeconds(), rawRefresh,
                Duration.between(now, refreshExpiresAt));
    }

    private String loginIdOf(Long userId) {
        return appUserRepository.findById(userId).map(AppUser::getLoginId).orElseThrow();
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 256비트 임의값이라 솔트·느린 해시가 필요 없다 — 무차별 대입으로 원문을 찾을 수 없다. */
    static String hash(String rawRefreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
