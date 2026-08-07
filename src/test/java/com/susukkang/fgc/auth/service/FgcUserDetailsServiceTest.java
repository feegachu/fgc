package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.mapper.AuthUserMapper;
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

@ExtendWith(MockitoExtension.class)
class FgcUserDetailsServiceTest {

    @Mock
    private AuthUserMapper authUserMapper;

    private FgcUserDetailsService service() {
        return new FgcUserDetailsService(authUserMapper);
    }

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
    @Test
    void activeUserGetsPrefixedRoleAndCarriesUserId() {
        given(authUserMapper.findByLoginId("settle01")).willReturn(user("ACTIVE"));

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
    @Test
    void lockedAccountIsNotAccountNonLocked() {
        given(authUserMapper.findByLoginId("settle01")).willReturn(user("LOCKED"));

        assertThat(service().loadUserByUsername("settle01").isAccountNonLocked()).isFalse();
    }

    @Test
    void disabledAccountIsNotEnabled() {
        given(authUserMapper.findByLoginId("settle01")).willReturn(user("DISABLED"));

        assertThat(service().loadUserByUsername("settle01").isEnabled()).isFalse();
    }

    // 없는 아이디는 UsernameNotFoundException — DaoAuthenticationProvider 가 BadCredentials 로 감춰 준다
    @Test
    void unknownLoginIdThrowsUsernameNotFound() {
        given(authUserMapper.findByLoginId("nobody")).willReturn(null);

        assertThatThrownBy(() -> service().loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
