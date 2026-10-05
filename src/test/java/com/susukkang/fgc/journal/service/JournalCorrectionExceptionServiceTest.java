package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionInsertCommand;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRequest;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.journal.repository.JournalCorrectionExceptionRepository;
import com.susukkang.fgc.journal.repository.JournalCorrectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 설명 : IF-API-36A 원장 정정 예외의 멱등 생성과 원분개 게이트 테스트
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class JournalCorrectionExceptionServiceTest {

    @Mock
    private JournalCorrectionRepository journalCorrectionRepository;
    @Mock
    private JournalCorrectionExceptionRepository exceptionRepository;
    @Mock
    private AuditLogService auditLogService;

    private JournalCorrectionExceptionService service;

    @BeforeEach
    void setUp() {
        service = new JournalCorrectionExceptionService(
                journalCorrectionRepository, exceptionRepository, auditLogService);
    }

    @Test
    void createsCaseAndInitialEvidenceActionOnce() {
        given(journalCorrectionRepository.findHeaderForUpdate(10L)).willReturn(postedHeader());
        given(exceptionRepository.findActiveBySource(10L, 9L)).willReturn(null);
        given(exceptionRepository.countBySource(10L, 9L)).willReturn(0);
        given(exceptionRepository.insertCase(any())).willReturn(1);
        given(exceptionRepository.findByExceptionKey(
                "JOURNAL_HEADER:10:JOURNAL_CORRECTION_REQUIRED:POLICY_VERSION:9:REQUEST:1"))
                .willReturn(new JournalCorrectionExceptionRow(30L, ExceptionStatus.NEW));

        var response = service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("금액 오류", "DOC-10"), 1L);

        assertThat(response.exceptionCaseId()).isEqualTo(30L);
        assertThat(response.created()).isTrue();
        assertThat(response.redirectUrl()).isEqualTo("/exceptions?selected=30");
        verify(exceptionRepository).insertInitialAction(eq(30L), any());
        verify(auditLogService).record(any());
    }

    @Test
    void repeatedRequestReturnsExistingCaseWithoutDuplicateHistory() {
        // FGC-FUN-052: 같은 원분개의 미종결 정정 예외를 재사용한다.
        given(journalCorrectionRepository.findHeaderForUpdate(10L)).willReturn(postedHeader());
        given(exceptionRepository.findActiveBySource(10L, 9L))
                .willReturn(new JournalCorrectionExceptionRow(30L, ExceptionStatus.IN_REVIEW));

        var response = service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("재요청", null), 1L);

        assertThat(response.created()).isFalse();
        assertThat(response.status()).isEqualTo(ExceptionStatus.IN_REVIEW);
        verify(exceptionRepository, never()).insertCase(any());
        verify(exceptionRepository, never()).insertInitialAction(any(), any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void conflictingRequestKeyReturnsExistingCaseWithoutDuplicateHistory() {
        given(journalCorrectionRepository.findHeaderForUpdate(10L)).willReturn(postedHeader());
        given(exceptionRepository.findByExceptionKey(
                "JOURNAL_HEADER:10:JOURNAL_CORRECTION_REQUIRED:POLICY_VERSION:9:REQUEST:1"))
                .willReturn(new JournalCorrectionExceptionRow(30L, ExceptionStatus.NEW));
        given(exceptionRepository.insertCase(any())).willReturn(0);

        var response = service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("중복 요청", null), 1L);

        assertThat(response.exceptionCaseId()).isEqualTo(30L);
        assertThat(response.created()).isFalse();
        verify(exceptionRepository, never()).insertInitialAction(any(), any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void nextRequestWithoutPolicyUsesNewLifecycleKeyAndNormalizesInput() {
        JournalCorrectionHeaderRow header = postedHeader();
        header.setPolicyVersionId(null);
        header.setJournalDate(LocalDate.of(2026, 8, 20));
        given(journalCorrectionRepository.findHeaderForUpdate(10L)).willReturn(header);
        given(exceptionRepository.countBySource(10L, null)).willReturn(2);
        given(exceptionRepository.insertCase(any())).willReturn(1);
        given(exceptionRepository.findByExceptionKey(
                "JOURNAL_HEADER:10:JOURNAL_CORRECTION_REQUIRED:POLICY_VERSION:NONE:REQUEST:3"))
                .willReturn(new JournalCorrectionExceptionRow(31L, ExceptionStatus.NEW));

        service.createOrGet(10L, new JournalCorrectionExceptionRequest("  재검토 사유  ", "  "), 1L);

        ArgumentCaptor<JournalCorrectionExceptionInsertCommand> captor =
                ArgumentCaptor.forClass(JournalCorrectionExceptionInsertCommand.class);
        verify(exceptionRepository).insertCase(captor.capture());
        JournalCorrectionExceptionInsertCommand command = captor.getValue();
        assertThat(command.exceptionKey()).endsWith("POLICY_VERSION:NONE:REQUEST:3");
        assertThat(command.validationMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(command.policyVersionId()).isNull();
        assertThat(command.reason()).isEqualTo("재검토 사유");
        assertThat(command.description()).isEqualTo("재검토 사유");
        assertThat(command.evidenceRef()).isNull();
        assertThat(command.requestedBy()).isEqualTo(1L);
        verify(exceptionRepository).insertInitialAction(31L, command);
    }

    @Test
    void initialActionFailureIsPropagatedBeforeWritingAudit() {
        given(journalCorrectionRepository.findHeaderForUpdate(10L)).willReturn(postedHeader());
        given(exceptionRepository.insertCase(any())).willReturn(1);
        given(exceptionRepository.findByExceptionKey(
                "JOURNAL_HEADER:10:JOURNAL_CORRECTION_REQUIRED:POLICY_VERSION:9:REQUEST:1"))
                .willReturn(new JournalCorrectionExceptionRow(30L, ExceptionStatus.NEW));
        doThrow(new DataIntegrityViolationException("initial action constraint failure"))
                .when(exceptionRepository).insertInitialAction(eq(30L), any());

        assertThatThrownBy(() -> service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("정정 요청", null), 1L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("initial action constraint failure");

        verify(auditLogService, never()).record(any());
    }

    @Test
    void reversedJournalCannotCreateCorrectionCase() {
        JournalCorrectionHeaderRow header = postedHeader();
        header.setStatus("REVERSED");
        given(journalCorrectionRepository.findHeaderForUpdate(10L)).willReturn(header);

        assertThatThrownBy(() -> service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("재요청", null), 1L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(error -> assertThat(((FgcBusinessException) error).getErrorCode())
                        .isEqualTo(FgcErrorCode.LEDG_004));

        verify(exceptionRepository, never()).insertCase(any());
    }

    private JournalCorrectionHeaderRow postedHeader() {
        JournalCorrectionHeaderRow header = new JournalCorrectionHeaderRow();
        header.setJournalHeaderId(10L);
        header.setJournalDate(LocalDate.of(2026, 8, 1));
        header.setJournalType("CONFIRMED_FC_PAYOUT");
        header.setValidationRunId(5L);
        header.setContractId(7L);
        header.setPolicyVersionId(9L);
        header.setStatus("POSTED");
        return header;
    }
}
