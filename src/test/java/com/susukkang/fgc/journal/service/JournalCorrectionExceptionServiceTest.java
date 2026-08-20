package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRequest;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.journal.mapper.JournalCorrectionExceptionMapper;
import com.susukkang.fgc.journal.mapper.JournalCorrectionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
    private JournalCorrectionMapper journalCorrectionMapper;
    @Mock
    private JournalCorrectionExceptionMapper exceptionMapper;
    @Mock
    private AuditLogService auditLogService;

    private JournalCorrectionExceptionService service;

    @BeforeEach
    void setUp() {
        service = new JournalCorrectionExceptionService(
                journalCorrectionMapper, exceptionMapper, auditLogService);
    }

    @Test
    void createsCaseAndInitialEvidenceActionOnce() {
        given(journalCorrectionMapper.findHeaderForUpdate(10L)).willReturn(postedHeader());
        given(exceptionMapper.findActiveBySource(10L, 9L)).willReturn(null);
        given(exceptionMapper.countBySource(10L, 9L)).willReturn(0);
        given(exceptionMapper.insertCase(any())).willReturn(1);
        given(exceptionMapper.findByExceptionKey(
                "JOURNAL_HEADER:10:JOURNAL_CORRECTION_REQUIRED:POLICY_VERSION:9:REQUEST:1"))
                .willReturn(new JournalCorrectionExceptionRow(30L, ExceptionStatus.NEW));
        given(exceptionMapper.insertInitialAction(any())).willReturn(1);

        var response = service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("금액 오류", "DOC-10"), 1L);

        assertThat(response.exceptionCaseId()).isEqualTo(30L);
        assertThat(response.created()).isTrue();
        assertThat(response.redirectUrl()).isEqualTo("/exceptions?selected=30");
        verify(exceptionMapper).insertInitialAction(any());
        verify(auditLogService).record(any());
    }

    @Test
    void repeatedRequestReturnsExistingCaseWithoutDuplicateHistory() {
        // FGC-FUN-052: 같은 원분개의 미종결 정정 예외를 재사용한다.
        given(journalCorrectionMapper.findHeaderForUpdate(10L)).willReturn(postedHeader());
        given(exceptionMapper.findActiveBySource(10L, 9L))
                .willReturn(new JournalCorrectionExceptionRow(30L, ExceptionStatus.IN_REVIEW));

        var response = service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("재요청", null), 1L);

        assertThat(response.created()).isFalse();
        assertThat(response.status()).isEqualTo(ExceptionStatus.IN_REVIEW);
        verify(exceptionMapper, never()).insertCase(any());
        verify(exceptionMapper, never()).insertInitialAction(any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void reversedJournalCannotCreateCorrectionCase() {
        JournalCorrectionHeaderRow header = postedHeader();
        header.setStatus("REVERSED");
        given(journalCorrectionMapper.findHeaderForUpdate(10L)).willReturn(header);

        assertThatThrownBy(() -> service.createOrGet(
                10L, new JournalCorrectionExceptionRequest("재요청", null), 1L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(error -> assertThat(((FgcBusinessException) error).getErrorCode())
                        .isEqualTo(FgcErrorCode.LEDG_004));

        verify(exceptionMapper, never()).insertCase(any());
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
