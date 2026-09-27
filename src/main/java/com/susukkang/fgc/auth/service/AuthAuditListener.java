package com.susukkang.fgc.auth.service;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
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
 * 설명 : 로그인·로그아웃을 audit_log 에 남긴다.
 * 화면정의서 AUTH-W01 "쓰기: audit_log (action_code = LOGIN_SUCCESS / LOGIN_FAIL / LOGOUT)".
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Component
public class AuthAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditListener.class);

    private static final String ENTITY_TYPE = "APP_USER";
    private static final String UNKNOWN_LOGIN_ID = "UNKNOWN";

    /*
     * 아래 두 길이는 audit_log 컬럼 정의(V1__baseline_v2_1_2.sql)와 반드시 같아야 한다.
     * 한 글자라도 넘치면 INSERT 가 통째로 실패하고, 그 실패는 record() 의 catch 가
     * ERROR 로그로만 남기므로 감사 행이 조용히 사라진다.
     * login_id 와 X-Request-Id 는 둘 다 바깥에서 들어오는 값이라 길이를 믿을 수 없다.
     */
    /** audit_log.entity_id varchar(100) */
    private static final int ENTITY_ID_MAX_LENGTH = 100;
    /** audit_log.request_id varchar(80) */
    private static final int REQUEST_ID_MAX_LENGTH = 80;

    private final AuditLogRepository auditLogRepository;

    /**
     * 설명 : 인증 이벤트의 감사 기록에 사용할 저장 전용 Repository를 주입한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    public AuthAuditListener(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 설명 : AuthenticationSuccessEvent 가 아니라 Interactive~ 를 듣는다.
     * 전자는 /api/** 의 HTTP Basic 인증에서도 요청마다 발행되어 감사로그가 호출 수만큼 쌓인다.
     * Interactive~ 는 폼 로그인 필터(AbstractAuthenticationProcessingFilter)만 발행한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @EventListener
    public void onLoginSuccess(InteractiveAuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        record("LOGIN_SUCCESS", userIdOf(authentication), authentication.getName(), null);
    }

    /**
     * 설명 : 자격증명 오류·잠금·비활성 모두 여기로 온다. 사용자에게는 구분 없이 같은 문구가 나가고, 사유는 감사로그에만 남는다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        String reason = event.getException() == null
                ? null
                : event.getException().getClass().getSimpleName();
        record("LOGIN_FAIL", null, event.getAuthentication().getName(), reason);
    }

    /**
     * 설명 : 로그아웃 성공 이벤트의 사용자와 요청 정보를 감사로그에 기록한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @EventListener
    public void onLogoutSuccess(LogoutSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        record("LOGOUT", userIdOf(authentication), authentication.getName(), null);
    }

    /**
     * 설명 : 인증 감사로그를 저장하고 저장 실패는 오류 로그로 남겨 인증 흐름을 유지한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private void record(String actionCode, Long userId, String loginId, String reason) {
        try {
            // 2026-09-27 hjKang - 인증 감사 엔티티를 생성해 Repository로 직접 저장한다.
            // 기존 코드: 저장 DTO를 Writer에 전달해 변환·저장을 위임했다.
            // 문제: 단순 저장을 위한 중간 계층으로 호출 경로가 길어졌다.
            // 개선: saveAndFlush를 try 안에서 호출해 저장 오류를 기존 catch에서 처리한다.
            AuditLog auditLog = AuditLog.create(userId, actionCode, ENTITY_TYPE, entityId(loginId),
                    null, null, reason, clamp(RequestIdContext.current(), REQUEST_ID_MAX_LENGTH),
                    clientIp(), null);
            auditLogRepository.saveAndFlush(auditLog);
        } catch (RuntimeException ex) {
            // ponytail: 감사 쓰기 실패를 ERROR 로그로만 남긴다. DB 일시 장애로 전원이 로그인 불가가 되는 편이 더 나쁘다.
            //           유실 자체를 막아야 하면 아웃박스 테이블 + 재시도로 승격할 것
            log.error("감사로그 기록 실패 actionCode={} loginId={}", actionCode, loginId, ex);
        }
    }

    /**
     * 설명 : 인증 주체가 FgcUserDetails이면 사용자 ID를 반환한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private static Long userIdOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof FgcUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    /**
     * 설명 : 로그인 ID를 감사 대상 식별자로 변환하고 누락 값과 컬럼 길이를 보정한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private static String entityId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return UNKNOWN_LOGIN_ID;
        }
        return clamp(loginId, ENTITY_ID_MAX_LENGTH);
    }

    /**
     * 설명 : 컬럼 길이를 넘는 값을 잘라 낸다. 감사 행을 통째로 잃느니 값이 잘리는 편이 낫다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private static String clamp(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * 설명 : 세 이벤트 모두 요청 스레드에서 발생하므로 현재 요청에서 바로 꺼낸다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private static String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
