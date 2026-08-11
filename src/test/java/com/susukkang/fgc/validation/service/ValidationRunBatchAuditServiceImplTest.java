package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationRunBatchAuditServiceImplTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    private final MonthlyValidationJobParameters parameters = new MonthlyValidationJobParameters(
            LocalDate.of(2026, 8, 1), 7L, ValidationRunType.MONTHLY, 12L, "request-123");

    @Test
    void recordsTriggeredByAndRequestIdWhenTheRunStarts() {
        when(auditLogMapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ValidationRunBatchAuditService service = new ValidationRunBatchAuditServiceImpl(auditLogMapper);

        service.recordStarted(100L, parameters);

        ArgumentCaptor<AuditLogInsertRow> captor = ArgumentCaptor.forClass(AuditLogInsertRow.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLogInsertRow row = captor.getValue();
        assertThat(row.getUserId()).isEqualTo(12L);
        assertThat(row.getRequestId()).isEqualTo("request-123");
        assertThat(row.getActionCode()).isEqualTo("VALIDATION_RUN_STARTED");
        assertThat(row.getEntityType()).isEqualTo("VALIDATION_RUN");
        assertThat(row.getEntityId()).isEqualTo("100");
    }

    @Test
    void recordsFailureReasonWithTheSameAuditContext() {
        when(auditLogMapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ValidationRunBatchAuditService service = new ValidationRunBatchAuditServiceImpl(auditLogMapper);

        service.recordFailed(100L, parameters, "Step capCheckStep failed");

        ArgumentCaptor<AuditLogInsertRow> captor = ArgumentCaptor.forClass(AuditLogInsertRow.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLogInsertRow row = captor.getValue();
        assertThat(row.getUserId()).isEqualTo(12L);
        assertThat(row.getRequestId()).isEqualTo("request-123");
        assertThat(row.getActionCode()).isEqualTo("VALIDATION_RUN_FAILED");
        assertThat(row.getReason()).isEqualTo("Step capCheckStep failed");
    }
}
