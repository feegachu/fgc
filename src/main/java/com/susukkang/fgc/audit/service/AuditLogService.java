package com.susukkang.fgc.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.web.RequestIdContext;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 설명 : FUN-061 핵심 업무 감사로그 공통 기록 서비스.
 *
 * 운영정책서 제51조: audit_log 는 범용 감사 트리거가 아니므로 정책·지급·예외·권한을 변경하는
 * 서비스가 같은 트랜잭션에서 감사행을 명시적으로 생성해야 한다. 이 컴포넌트는 그 신규 기록
 * 지점들이 공유하는 관심사(사용자 해석, JSON 직렬화, 컬럼 길이 clamp, 실패 전파)를 모은다.
 *
 * 실패 정책: 기록 실패는 예외로 전파되어 호출자의 업무 트랜잭션을 함께 롤백한다(기록률 100%
 * 인수조건). AuthAuditListener 의 실패-삼킴(로그인 불능 방지)과는 반대의 의도적 정책이므로
 * 로그인 감사를 이 서비스로 옮기지 말 것.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Component
@RequiredArgsConstructor
public class AuditLogService {

    /** audit_log.entity_id varchar(100) */
    private static final int ENTITY_ID_MAX_LENGTH = 100;
    /** audit_log.reason varchar(1000) */
    private static final int REASON_MAX_LENGTH = 1000;
    /** audit_log.request_id varchar(80) */
    private static final int REQUEST_ID_MAX_LENGTH = 80;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 설명 : 호출자 트랜잭션에 참여해 감사행 1건을 INSERT 한다.
     * userId 를 지정하지 않으면 SecurityContext 의 로그인 사용자로 해석하고,
     * 그래도 없으면 null(화면 표기 "BATCH")로 남긴다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    public void record(AuditEvent event) {
        // 2026-09-27 hjKang - 기존 서비스에서 신규 엔티티를 생성하고 Repository를 직접 호출한다.
        // 기존 코드: 저장 DTO를 Writer에 전달하고 고정 반환값 1을 검사했다.
        // 문제: 단순 변환·저장에 별도 계층이 필요했고 반환값은 실제 영향 행 수가 아니었다.
        // 개선: 엔티티 생성 후 saveAndFlush로 저장하며 DB 오류를 업무 호출자에게 전파한다.
        AuditLog auditLog = AuditLog.create(
                event.userId() != null ? event.userId() : currentUserId(),
                event.actionCode(), event.entityType(), clamp(event.entityId(), ENTITY_ID_MAX_LENGTH),
                toJson(event.before()), toJson(event.after()), clamp(event.reason(), REASON_MAX_LENGTH),
                clamp(RequestIdContext.current(), REQUEST_ID_MAX_LENGTH), clientIp(), event.policyVersionId());
        auditLogRepository.saveAndFlush(auditLog);
    }

    /**
     * 설명 : 감사 사건 1건. actionCode(varchar 50)·entityType(varchar 60)은 코드가 정하는 상수라
     * clamp 하지 않는다 — 길이 초과는 버그이므로 INSERT 실패로 드러나 트랜잭션이 롤백된다.
     * before/after 는 임의 객체를 받아 JSON 으로 직렬화한다(null 허용).
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Builder
    public record AuditEvent(
            String actionCode,
            String entityType,
            String entityId,
            Long userId,
            Object before,
            Object after,
            String reason,
            Long policyVersionId) {
    }

    /**
     * 설명 : 감사 대상 객체를 JSON 문자열로 직렬화하고 실패 시 예외를 전파한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("감사로그 값 직렬화 실패", ex);
        }
    }

    /**
     * 설명 : 현재 인증 주체에서 사용자 ID를 조회하고 인증 정보가 없으면 null을 반환한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof FgcUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    /**
     * 설명 : 배치 스레드처럼 요청 컨텍스트가 없으면 null.
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

    /**
     * 설명 : 문자열이 DB 컬럼의 최대 길이를 넘으면 허용 길이까지 자른다.
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
}
