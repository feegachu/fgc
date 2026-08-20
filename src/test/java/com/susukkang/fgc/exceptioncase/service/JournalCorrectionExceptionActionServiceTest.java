package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionLineRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionExceptionTarget;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseActionMapper;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.JournalRepostCommand;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 설명 : IF-API-44A 실제 원장 정정 성공 후 예외 종결 순서 테스트
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class JournalCorrectionExceptionActionServiceTest {

    @Mock
    private ExceptionCaseActionMapper actionMapper;
    @Mock
    private JournalCorrectionService journalCorrectionService;
    @Mock
    private ExceptionCaseService exceptionCaseService;

    private JournalCorrectionExceptionActionService service;

    @BeforeEach
    void setUp() {
        service = new JournalCorrectionExceptionActionService(
                actionMapper, journalCorrectionService, exceptionCaseService);
    }

    @Test
    void resolvesExceptionOnlyAfterReverseAndRepostSucceeds() {
        given(actionMapper.findJournalCorrectionTargetForUpdate(30L))
                .willReturn(target("JOURNAL_CORRECTION_REQUIRED", ExceptionStatus.IN_REVIEW));
        given(journalCorrectionService.reverseAndRepost(any(JournalRepostCommand.class)))
                .willReturn(new JournalCorrectionResult(21L, 10L, 22L, "JCG-10-test"));
        given(exceptionCaseService.actionAfterJournalCorrection(eq(30L), any(ExceptionActionRequest.class),
                eq(1L), eq("settle01")))
                .willReturn(new ExceptionActionResponse(
                        40L, 2, ExceptionStatus.IN_REVIEW, ExceptionStatus.RESOLVED,
                        ExceptionActionType.CORRECT.name(), "금액 정정", "DOC-10",
                        1L, "settle01", OffsetDateTime.parse("2026-08-20T10:00:00+09:00")));

        var response = service.correct(30L, request(), 1L, "settle01");

        assertThat(response.originalJournalHeaderId()).isEqualTo(10L);
        assertThat(response.reversalJournalHeaderId()).isEqualTo(21L);
        assertThat(response.repostedJournalHeaderId()).isEqualTo(22L);
        assertThat(response.toStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        InOrder order = inOrder(journalCorrectionService, exceptionCaseService);
        order.verify(journalCorrectionService).reverseAndRepost(any(JournalRepostCommand.class));
        order.verify(exceptionCaseService).actionAfterJournalCorrection(
                eq(30L), any(ExceptionActionRequest.class), eq(1L), eq("settle01"));
    }

    @Test
    void failedRepostDoesNotWriteResolutionAction() {
        given(actionMapper.findJournalCorrectionTargetForUpdate(30L))
                .willReturn(target("JOURNAL_CORRECTION_REQUIRED", ExceptionStatus.IN_REVIEW));
        willThrow(new FgcBusinessException(FgcErrorCode.LEDG_001))
                .given(journalCorrectionService)
                .reverseAndRepost(any(JournalRepostCommand.class));

        assertThatThrownBy(() -> service.correct(30L, request(), 1L, "settle01"))
                .isInstanceOf(FgcBusinessException.class);

        verify(exceptionCaseService, never()).actionAfterJournalCorrection(any(), any(), any(), any());
    }

    @Test
    void rejectsNonJournalCorrectionException() {
        given(actionMapper.findJournalCorrectionTargetForUpdate(30L))
                .willReturn(target("JOURNAL_IMBALANCE", ExceptionStatus.IN_REVIEW));

        assertThatThrownBy(() -> service.correct(30L, request(), 1L, "settle01"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(error -> assertThat(((FgcBusinessException) error).getErrorCode())
                        .isEqualTo(FgcErrorCode.EXCP_004));

        verify(journalCorrectionService, never()).reverseAndRepost(any(JournalRepostCommand.class));
    }

    private JournalCorrectionExceptionTarget target(String type, ExceptionStatus status) {
        return new JournalCorrectionExceptionTarget(
                30L, type, status, "JOURNAL_HEADER", "10");
    }

    private JournalCorrectionActionRequest request() {
        return new JournalCorrectionActionRequest(
                "금액 정정", "DOC-10", LocalDate.of(2026, 8, 20), "재기표",
                List.of(
                        new JournalCorrectionActionLineRequest(
                                1, "CONFIRMED_PAYOUT_EXPENSE",
                                BigDecimal.valueOf(1000), BigDecimal.ZERO, "차변"),
                        new JournalCorrectionActionLineRequest(
                                2, "CONFIRMED_PAYOUT_PAYABLE",
                                BigDecimal.ZERO, BigDecimal.valueOf(1000), "대변")
                ));
    }
}
