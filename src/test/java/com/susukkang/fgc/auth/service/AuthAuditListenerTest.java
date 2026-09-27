package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 인증 이벤트의 감사 저장 실패 처리와 기록 값 보정을 검증하는 단위 테스트.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@ExtendWith(MockitoExtension.class)
class AuthAuditListenerTest {
    @Mock
    private AuditLogRepository repository;

    /**
     * 설명 : 감사 저장 예외가 로그인 성공·실패 및 로그아웃 이벤트 리스너 밖으로 전파되지 않는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void auditFailureDoesNotEscapeLoginOrLogoutListeners() {
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("audit unavailable"));
        var listener = new AuthAuditListener(repository);
        var authentication = UsernamePasswordAuthenticationToken.unauthenticated("settle01", "unused");

        assertThatCode(() -> {
            listener.onLoginSuccess(new InteractiveAuthenticationSuccessEvent(authentication, getClass()));
            listener.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
                    authentication, new BadCredentialsException("bad credentials")));
            listener.onLogoutSuccess(new LogoutSuccessEvent(authentication));
        }).doesNotThrowAnyException();
        verify(repository, times(3)).saveAndFlush(any());
    }

    /**
     * 설명 : 로그인 실패 원인이 감사로그에 유지되고 긴 로그인 ID가 컬럼 길이로 보정되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void failureKeepsReasonAndClampsUntrustedLoginId() {
        var listener = new AuthAuditListener(repository);
        listener.onLoginFailure(new AuthenticationFailureBadCredentialsEvent(
                UsernamePasswordAuthenticationToken.unauthenticated("x".repeat(101), "unused"),
                new BadCredentialsException("bad credentials")));

        var captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).extracting("auditLogId", "userId", "entityId", "actionCode", "reason")
                .containsExactly(null, null, "x".repeat(100), "LOGIN_FAIL", "BadCredentialsException");
    }
}
