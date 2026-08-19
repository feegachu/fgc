package com.susukkang.fgc.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
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
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * FUN-061 공통 감사 기록 서비스 단위 테스트.
 * 기록 실패가 예외로 전파되는지(기록률 100% — 업무 트랜잭션 동반 롤백)와
 * 컬럼 길이 clamp(감사행 조용한 유실 방지)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogMapper, new ObjectMapper());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("before/after 를 JSON 으로 직렬화하고 entityId·reason 을 컬럼 길이로 자른다")
    void serializesValuesAndClampsLengths() {
        given(auditLogMapper.insert(any())).willReturn(1);

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

        ArgumentCaptor<AuditLogInsertRow> captor = ArgumentCaptor.forClass(AuditLogInsertRow.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLogInsertRow row = captor.getValue();
        assertThat(row.getActionCode()).isEqualTo("CONTRACT_CREATED");
        assertThat(row.getEntityType()).isEqualTo("CONTRACT");
        assertThat(row.getEntityId()).hasSize(100);
        assertThat(row.getReason()).hasSize(1000);
        assertThat(row.getUserId()).isEqualTo(12L);
        assertThat(row.getBeforeValue()).isEqualTo("{\"status\":\"DRAFT\"}");
        assertThat(row.getAfterValue()).isEqualTo("{\"status\":\"CONFIRMED\"}");
        assertThat(row.getPolicyVersionId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("userId 를 지정하지 않으면 SecurityContext 의 로그인 사용자로 해석한다")
    void resolvesUserIdFromSecurityContext() {
        given(auditLogMapper.insert(any())).willReturn(1);
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

        ArgumentCaptor<AuditLogInsertRow> captor = ArgumentCaptor.forClass(AuditLogInsertRow.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("로그인 사용자도 명시 userId 도 없으면 null(배치 표기 BATCH)로 남긴다")
    void leavesUserIdNullWithoutAuthentication() {
        given(auditLogMapper.insert(any())).willReturn(1);

        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("VALIDATION_RUN_STARTED")
                .entityType("VALIDATION_RUN")
                .entityId("1")
                .build());

        ArgumentCaptor<AuditLogInsertRow> captor = ArgumentCaptor.forClass(AuditLogInsertRow.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getBeforeValue()).isNull();
        assertThat(captor.getValue().getAfterValue()).isNull();
    }

    @Test
    @DisplayName("INSERT 가 1행이 아니면 예외를 던져 호출자 트랜잭션을 롤백시킨다 — 기록률 100% 인수조건")
    void throwsWhenInsertDoesNotAffectOneRow() {
        given(auditLogMapper.insert(any())).willReturn(0);

        assertThatThrownBy(() -> auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("CONTRACT_UPDATED")
                .entityType("CONTRACT")
                .entityId("9")
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTRACT_UPDATED");
    }
}
