package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// FUN-001 개발 순서 14 - Test 1

/**
 * 설명 : JPA 사용자 조회 결과의 계정 상태와 권한 및 사용자 식별 정보 변환을 검증하는 단위 테스트.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@ExtendWith(MockitoExtension.class)
class FgcUserDetailsServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    /**
     * 설명 : 사용자 Repository 모의 객체를 주입한 인증 서비스를 생성한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private FgcUserDetailsService service() {
        return new FgcUserDetailsService(appUserRepository);
    }

    /**
     * 설명 : 지정한 계정 상태를 가진 인증 조회용 사용자 DTO를 생성한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private AppUserView user(String accountStatus) {
        AppUserView view = new AppUserView();
        view.setUserId(3L);
        view.setLoginId("settle01");
        view.setPasswordHash("$2a$10$hash");
        view.setUserName("정산담당자");
        view.setRoleCode("SETTLEMENT");
        view.setAccountStatus(accountStatus);
        return view;
    }

    // 정상 계정은 ROLE_ 접두사를 붙인 권한 1개와 user_id 를 함께 싣는다
    /**
     * 설명 : 활성 사용자의 권한 접두사와 사용자 식별 정보 및 계정 상태를 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void activeUserGetsPrefixedRoleAndCarriesUserId() {
        given(appUserRepository.findByLoginId("settle01")).willReturn(user("ACTIVE"));

        UserDetails details = service().loadUserByUsername("settle01");

        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SETTLEMENT");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(((FgcUserDetails) details).getUserId()).isEqualTo(3L);
        assertThat(((FgcUserDetails) details).getUserName()).isEqualTo("정산담당자");
    }

    // 화면정의서 AUTH-W01: 잠긴 계정(account_status='LOCKED')의 로그인은 거절한다
    /**
     * 설명 : 잠긴 계정의 계정 잠금 해제 여부가 false로 반환되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void lockedAccountIsNotAccountNonLocked() {
        given(appUserRepository.findByLoginId("settle01")).willReturn(user("LOCKED"));

        assertThat(service().loadUserByUsername("settle01").isAccountNonLocked()).isFalse();
    }

    /**
     * 설명 : 비활성 계정의 활성 여부가 false로 반환되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void disabledAccountIsNotEnabled() {
        given(appUserRepository.findByLoginId("settle01")).willReturn(user("DISABLED"));

        assertThat(service().loadUserByUsername("settle01").isEnabled()).isFalse();
    }

    // 없는 아이디는 UsernameNotFoundException — DaoAuthenticationProvider 가 BadCredentials 로 감춰 준다
    /**
     * 설명 : 존재하지 않는 로그인 ID에 기존 사용자 조회 실패 예외가 발생하는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void unknownLoginIdThrowsUsernameNotFound() {
        given(appUserRepository.findByLoginId("nobody")).willReturn(null);

        assertThatThrownBy(() -> service().loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
