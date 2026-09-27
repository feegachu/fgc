package com.susukkang.fgc.auth.repository;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 설명 : 공용 사용자 Repository. 인증 조회는 역할까지 한 번에 DTO로 반환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    /**
     * 설명 : 로그인 ID로 사용자와 역할을 함께 조회해 인증용 DTO를 반환한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Query("""
            SELECT new com.susukkang.fgc.auth.dto.AppUserView(
                u.userId,
                u.loginId,
                u.passwordHash,
                u.userName,
                r.roleCode,
                u.accountStatus)
            FROM AppUser u JOIN u.role r
            WHERE u.loginId = :loginId
            """)
    AppUserView findByLoginId(@Param("loginId") String loginId);
}
