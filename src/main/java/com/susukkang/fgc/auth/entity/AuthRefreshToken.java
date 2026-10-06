package com.susukkang.fgc.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 설명 : 2차 JWT Refresh 토큰 행(V43). 원문은 저장하지 않고 SHA-256 해시만 둔다(#400).
 * 회전은 같은 sessionId 로 새 행을 잇고, 이전 행은 ROTATED 로 무효화한다.
 */
@Entity
@Table(name = "auth_refresh_token", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthRefreshToken {

    /** V43 ck_auth_refresh_token_reason 과 같은 값. */
    public enum RevokedReason { LOGOUT, ROTATED, REUSE_DETECTED, SUPERSEDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refresh_token_id")
    private Long refreshTokenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 20)
    private RevokedReason revokedReason;

    public static AuthRefreshToken issue(Long userId, UUID sessionId, String tokenHash,
                                         OffsetDateTime expiresAt) {
        AuthRefreshToken token = new AuthRefreshToken();
        token.userId = userId;
        token.sessionId = sessionId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        return token;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke(RevokedReason reason, OffsetDateTime now) {
        this.revokedAt = now;
        this.revokedReason = reason;
    }
}
