package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.batch.MonthlyValidationJobTrigger;
import com.susukkang.fgc.validation.batch.daily.DailyChangedContractJobTrigger;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * IF-API-48 실행 guard — CREATED만 기동, 나머지는 상태별 에러코드 (FUN-042·043).
 * 1차는 재기동이 없다: FAILED도 VRUN_004다(새 실행 유도, FUN-045는 2차).
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunExecuteServiceImplTest {

    @Mock
    private ValidationRunMapper validationRunMapper;

    @Mock
    private MonthlyValidationJobTrigger monthlyValidationJobTrigger;

    @Mock
    private DailyChangedContractJobTrigger dailyChangedContractJobTrigger;

    @InjectMocks
    private ValidationRunExecuteServiceImpl service;

    private ValidationRunRow row(String status) {
        return row(status, "MONTHLY");
    }

    private ValidationRunRow row(String status, String runType) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(100L);
        row.setValidationMonth(LocalDate.of(2026, 7, 1));
        row.setRunNo(1);
        row.setRunType(runType);
        row.setStatus(status);
        row.setCurrentStep(0);
        row.setTriggeredBy(1L);
        return row;
    }

    @Test
    void returns404WhenRunNotFound() {
        given(validationRunMapper.findById(100L)).willReturn(null);

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));

        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    @Test
    void rejectsFinalizedRunWithVrun003() {
        given(validationRunMapper.findById(100L)).willReturn(row("FINALIZED"));

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_003));

        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "COMPLETED", "FAILED"})
    void rejectsNonCreatedRunWithVrun004(String status) {
        given(validationRunMapper.findById(100L)).willReturn(row(status));

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_004);
                    assertThat(e.getParams()).containsEntry("from", status).containsEntry("to", "RUNNING");
                });

        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    @Test
    void launchesCreatedRunAndReturnsRow() {
        ValidationRunRow created = row("CREATED");
        given(validationRunMapper.findById(100L)).willReturn(created);

        ValidationRunRow result = service.execute(100L, 9L, "req-1");

        assertThat(result).isSameAs(created);
        // JobParameters는 트리거가 행 값으로 만든다 — 실행자(9L)가 아닌 행이 그대로 전달되는지
        verify(monthlyValidationJobTrigger).launch(created, "req-1");
        verify(dailyChangedContractJobTrigger, never()).runManual(anyLong(), anyString());
    }

    /** MANUAL_CONTRACT는 MonthlyValidationJob이 아니라 DailyChangedContractJob으로 가야 한다(코드리뷰 반영). */
    @Test
    void launchesManualContractRunViaDailyTrigger() {
        ValidationRunRow created = row("CREATED", "MANUAL_CONTRACT");
        given(validationRunMapper.findById(100L)).willReturn(created);

        ValidationRunRow result = service.execute(100L, 9L, "req-1");

        assertThat(result).isSameAs(created);
        // 행의 원래 생성자(triggeredBy=1L)를 그대로 넘겨야 오늘의 같은 행을 재사용한다 —
        // 실행 버튼을 누른 사용자(9L)가 아니다.
        verify(dailyChangedContractJobTrigger).runManual(1L, "req-1");
        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    /** 트리거 대기열 포화(AbortPolicy) — 500 이 아니라 재시도 안내가 있는 VRUN_005(409)로 매핑된다. */
    @Test
    void mapsExecutorRejectionToVrun005() {
        ValidationRunRow created = row("CREATED");
        given(validationRunMapper.findById(100L)).willReturn(created);
        given(monthlyValidationJobTrigger.launch(created, "req-1"))
                .willThrow(new java.util.concurrent.RejectedExecutionException("queue full"));

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_005));
    }
}
