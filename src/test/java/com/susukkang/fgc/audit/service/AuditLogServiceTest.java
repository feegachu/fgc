package com.susukkang.fgc.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 설명 : FUN-061 공통 감사 기록 서비스 단위 테스트.
 * 기록 실패가 예외로 전파되는지(기록률 100% — 업무 트랜잭션 동반 롤백)와
 * 컬럼 길이 clamp(감사행 조용한 유실 방지)를 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    /**
     * 설명 : 감사 저장 Repository 모의 객체와 JSON 변환기를 사용하는 서비스를 준비한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository, new ObjectMapper());
    }

    /**
     * 설명 : 테스트 간 인증 정보가 공유되지 않도록 SecurityContext를 비운다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 설명 : 변경 전후 값의 JSON 직렬화와 감사 대상 및 사유의 컬럼 길이 제한을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("before/after 를 JSON 으로 직렬화하고 entityId·reason 을 컬럼 길이로 자른다")
    void serializesValuesAndClampsLengths() {
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("CONTRACT_CREATED")
                .entityType("CONTRACT")
                .entityId("x".repeat(150))
                .userId(12L)
                .before(Map.of("status", "DRAFT"))
                .after(Map.of("status", "CONFIRMED"))
                .reason("r".repeat(1100))
                .policyVersionId(3L)
                .build());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue())
                .extracting("auditLogId", "actionCode", "entityType", "entityId", "reason", "userId",
                        "beforeValue", "afterValue", "policyVersionId")
                .containsExactly(null, "CONTRACT_CREATED", "CONTRACT", "x".repeat(100), "r".repeat(1000),
                        12L, "{\"status\":\"DRAFT\"}", "{\"status\":\"CONFIRMED\"}", 3L);
    }

    /**
     * 설명 : 사용자 ID가 지정되지 않으면 인증 주체의 사용자 ID로 기록되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("userId 를 지정하지 않으면 SecurityContext 의 로그인 사용자로 해석한다")
    void resolvesUserIdFromSecurityContext() {
        AppUserView view = new AppUserView();
        view.setUserId(7L);
        view.setLoginId("settle01");
        view.setPasswordHash("x");
        view.setUserName("정산담당");
        view.setRoleCode("SETTLEMENT");
        FgcUserDetails details = new FgcUserDetails(view, true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));

        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("PAYMENT_CREATED")
                .entityType("COMMISSION_PAYMENT")
                .entityId("1")
                .build());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).extracting("userId").isEqualTo(7L);
    }

    /**
     * 설명 : 사용자 및 인증 정보가 없는 감사 기록의 사용자 ID와 변경 값이 null로 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    @DisplayName("로그인 사용자도 명시 userId 도 없으면 null(배치 표기 BATCH)로 남긴다")
    void leavesUserIdNullWithoutAuthentication() {
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("VALIDATION_RUN_STARTED")
                .entityType("VALIDATION_RUN")
                .entityId("1")
                .build());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).extracting("userId", "beforeValue", "afterValue")
                .containsOnlyNulls();
    }

    /**
     * 설명 : 감사 저장 중 발생한 DB 예외가 업무 호출자에게 그대로 전파되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void propagatesDatabaseFailureToBusinessCaller() {
        var failure = new DataIntegrityViolationException("audit unavailable");
        given(auditLogRepository.saveAndFlush(any())).willThrow(failure);

        assertThatThrownBy(() -> auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("CONTRACT_UPDATED").entityType("CONTRACT").entityId("9").build()))
                .isSameAs(failure);
    }
}
