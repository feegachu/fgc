package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.web.RequestIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// FUN-001 개발 순서 11

/**
 * 로그인·로그아웃을 audit_log 에 남긴다.
 * 화면정의서 AUTH-W01 "쓰기: audit_log (action_code = LOGIN_SUCCESS / LOGIN_FAIL / LOGOUT)".
 */
@Component
public class AuthAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditListener.class);

    private static final String ENTITY_TYPE = "APP_USER";
    private static final String UNKNOWN_LOGIN_ID = "UNKNOWN";
    /** audit_log.entity_id 는 varchar(100) — 비정상적으로 긴 입력 때문에 감사 기록 자체를 잃지 않도록 자른다 */
    private static final int ENTITY_ID_MAX_LENGTH = 100;

    private final AuditLogMapper auditLogMapper;

    public AuthAuditListener(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * AuthenticationSuccessEvent 가 아니라 Interactive~ 를 듣는다.
     * 전자는 /api/** 의 HTTP Basic 인증에서도 요청마다 발행되어 감사로그가 호출 수만큼 쌓인다.
     * Interactive~ 는 폼 로그인 필터(AbstractAuthenticationProcessingFilter)만 발행한다.
     */
    @EventListener
    public void onLoginSuccess(InteractiveAuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        record("LOGIN_SUCCESS", userIdOf(authentication), authentication.getName(), null);
    }

    /** 자격증명 오류·잠금·비활성 모두 여기로 온다. 사용자에게는 구분 없이 같은 문구가 나가고, 사유는 감사로그에만 남는다. */
    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        String reason = event.getException() == null
                ? null
                : event.getException().getClass().getSimpleName();
        record("LOGIN_FAIL", null, event.getAuthentication().getName(), reason);
    }

    @EventListener
    public void onLogoutSuccess(LogoutSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        record("LOGOUT", userIdOf(authentication), authentication.getName(), null);
    }

    private void record(String actionCode, Long userId, String loginId, String reason) {
        try {
            auditLogMapper.insert(AuditLogInsertRow.builder()
                    .userId(userId)
                    .actionCode(actionCode)
                    .entityType(ENTITY_TYPE)
                    .entityId(entityId(loginId))
                    .reason(reason)
                    .requestId(RequestIdContext.current())
                    .clientIp(clientIp())
                    .build());
        } catch (RuntimeException ex) {
            // ponytail: 감사 쓰기 실패를 ERROR 로그로만 남긴다. DB 일시 장애로 전원이 로그인 불가가 되는 편이 더 나쁘다.
            //           유실 자체를 막아야 하면 아웃박스 테이블 + 재시도로 승격할 것
            log.error("감사로그 기록 실패 actionCode={} loginId={}", actionCode, loginId, ex);
        }
    }

    private static Long userIdOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof FgcUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    private static String entityId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return UNKNOWN_LOGIN_ID;
        }
        return loginId.length() > ENTITY_ID_MAX_LENGTH
                ? loginId.substring(0, ENTITY_ID_MAX_LENGTH)
                : loginId;
    }

    /** 세 이벤트 모두 요청 스레드에서 발생하므로 현재 요청에서 바로 꺼낸다. */
    private static String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
