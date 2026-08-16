package com.susukkang.fgc.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
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
 * FUN-061 핵심 업무 감사로그 공통 기록 서비스.
 *
 * 운영정책서 제51조: audit_log 는 범용 감사 트리거가 아니므로 정책·지급·예외·권한을 변경하는
 * 서비스가 같은 트랜잭션에서 감사행을 명시적으로 생성해야 한다. 이 컴포넌트는 그 신규 기록
 * 지점들이 공유하는 관심사(사용자 해석, JSON 직렬화, 컬럼 길이 clamp, 실패 전파)를 모은다.
 *
 * 실패 정책: 기록 실패는 예외로 전파되어 호출자의 업무 트랜잭션을 함께 롤백한다(기록률 100%
 * 인수조건). AuthAuditListener 의 실패-삼킴(로그인 불능 방지)과는 반대의 의도적 정책이므로
 * 로그인 감사를 이 서비스로 옮기지 말 것.
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

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 호출자 트랜잭션에 참여해 감사행 1건을 INSERT 한다.
     * userId 를 지정하지 않으면 SecurityContext 의 로그인 사용자로 해석하고,
     * 그래도 없으면 null(화면 표기 "BATCH")로 남긴다.
     */
    public void record(AuditEvent event) {
        int affected = auditLogMapper.insert(AuditLogInsertRow.builder()
                .userId(event.userId() != null ? event.userId() : currentUserId())
                .actionCode(event.actionCode())
                .entityType(event.entityType())
                .entityId(clamp(event.entityId(), ENTITY_ID_MAX_LENGTH))
                .beforeValue(toJson(event.before()))
                .afterValue(toJson(event.after()))
                .reason(clamp(event.reason(), REASON_MAX_LENGTH))
                .requestId(clamp(RequestIdContext.current(), REQUEST_ID_MAX_LENGTH))
                .clientIp(clientIp())
                .policyVersionId(event.policyVersionId())
                .build());
        if (affected != 1) {
            throw new IllegalStateException(
                    "감사로그 기록 실패 actionCode=" + event.actionCode()
                            + " entityId=" + event.entityId());
        }
    }

    /**
     * 감사 사건 1건. actionCode(varchar 50)·entityType(varchar 60)은 코드가 정하는 상수라
     * clamp 하지 않는다 — 길이 초과는 버그이므로 INSERT 실패로 드러나 트랜잭션이 롤백된다.
     * before/after 는 임의 객체를 받아 JSON 으로 직렬화한다(null 허용).
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

    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof FgcUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    /** 배치 스레드처럼 요청 컨텍스트가 없으면 null. */
    private static String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    private static String clamp(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
