package com.susukkang.fgc.auth.repository;

import com.susukkang.fgc.auth.entity.AuthRefreshToken;
import com.susukkang.fgc.auth.entity.AuthRefreshToken.RevokedReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 설명 : Refresh 토큰 저장소(#400). 쓰기는 모두 AuthTokenService 가 app_user 행을 잠근
 * 트랜잭션 안에서만 한다 — 잠금 없이 쓰면 계정당 활성 1행 규칙이 경합에 깨진다.
 */
public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, Long> {

    /**
     * 잠그기 전에 어느 사용자 행을 잠글지만 알아낸다. 엔티티를 영속성 컨텍스트에 올리지 않으려고
     * 스칼라로 읽는다 — 먼저 올려 두면 잠금 뒤 다시 읽어도 잠그기 전 상태가 그대로 돌아온다.
     */
    @Query("SELECT t.userId FROM AuthRefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);

    /** Access 토큰 sid 판정용. 회전된 세션은 최신 행이 활성 행이다. */
    Optional<AuthRefreshToken> findFirstBySessionIdOrderByRefreshTokenIdDesc(UUID sessionId);

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuthRefreshToken t
               SET t.revokedAt = :now, t.revokedReason = :reason
             WHERE t.userId = :userId AND t.revokedAt IS NULL
            """)
    int revokeActiveByUserId(@Param("userId") Long userId,
                             @Param("reason") RevokedReason reason,
                             @Param("now") OffsetDateTime now);
}
