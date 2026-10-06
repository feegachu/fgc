package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.batch.MonthlyValidationJobTrigger;
import com.susukkang.fgc.validation.batch.daily.DailyChangedContractJobTrigger;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private ValidationRunRepository validationRunRepository;

    @Mock
    private MonthlyValidationJobTrigger monthlyValidationJobTrigger;

    @Mock
    private DailyChangedContractJobTrigger dailyChangedContractJobTrigger;

    @InjectMocks
    private ValidationRunExecuteServiceImpl service;

    private ValidationRun run(ValidationRunStatus status) {
        return run(status, "MONTHLY");
    }

    private ValidationRun run(ValidationRunStatus status, String runType) {
        ValidationRun run = ValidationRun.builder()
                .validationMonth(LocalDate.of(2026, 7, 1))
                .runNo(1)
                .runType(runType)
                .triggeredBy(1L)
                .build();
        ReflectionTestUtils.setField(run, "validationRunId", 100L);
        ReflectionTestUtils.setField(run, "status", status);
        ReflectionTestUtils.setField(run, "currentStep", 0);
        return run;
    }

    @Test
    void returns404WhenRunNotFound() {
        given(validationRunRepository.findById(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));

        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    @Test
    void rejectsFinalizedRunWithVrun003() {
        given(validationRunRepository.findById(100L)).willReturn(Optional.of(run(ValidationRunStatus.FINALIZED)));

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_003));

        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "COMPLETED", "FAILED"})
    void rejectsNonCreatedRunWithVrun004(String status) {
        ValidationRunStatus target = ValidationRunStatus.valueOf(status);
        given(validationRunRepository.findById(100L)).willReturn(Optional.of(run(target)));

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_004);
                    assertThat(e.getParams()).containsEntry("from", target).containsEntry("to", "RUNNING");
                });

        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    @Test
    void launchesCreatedRunAndReturnsRow() {
        given(validationRunRepository.findById(100L)).willReturn(Optional.of(run(ValidationRunStatus.CREATED)));

        ValidationRunRow result = service.execute(100L, 9L, "req-1");

        assertThat(result.getValidationRunId()).isEqualTo(100L);
        assertThat(result.getStatus()).isEqualTo("CREATED");
        // JobParameters는 트리거가 행 값으로 만든다 — 실행자(9L)가 아닌 행이 그대로 전달되는지
        ArgumentCaptor<ValidationRunRow> captor = ArgumentCaptor.forClass(ValidationRunRow.class);
        verify(monthlyValidationJobTrigger).launch(captor.capture(), eq("req-1"));
        assertThat(captor.getValue().getValidationRunId()).isEqualTo(100L);
        assertThat(captor.getValue().getStatus()).isEqualTo("CREATED");
        verify(dailyChangedContractJobTrigger, never()).runManual(any(), anyLong(), anyString());
    }

    /** MANUAL_CONTRACT는 MonthlyValidationJob이 아니라 DailyChangedContractJob으로 가야 한다(코드리뷰 반영). */
    @Test
    void launchesManualContractRunViaDailyTrigger() {
        given(validationRunRepository.findById(100L))
                .willReturn(Optional.of(run(ValidationRunStatus.CREATED, "MANUAL_CONTRACT")));

        ValidationRunRow result = service.execute(100L, 9L, "req-1");

        assertThat(result.getValidationRunId()).isEqualTo(100L);
        // validationRunId(100L)를 그대로 넘겨야 그 행을 정확히 이어받는다. triggeredBy도
        // 행의 원래 생성자(1L)를 넘겨야 한다 — 실행 버튼을 누른 사용자(9L)가 아니다.
        verify(dailyChangedContractJobTrigger).runManual(100L, 1L, "req-1");
        verify(monthlyValidationJobTrigger, never()).launch(any(), anyString());
    }

    /** 트리거 대기열 포화(AbortPolicy) — 500 이 아니라 재시도 안내가 있는 VRUN_005(409)로 매핑된다. */
    @Test
    void mapsExecutorRejectionToVrun005() {
        given(validationRunRepository.findById(100L)).willReturn(Optional.of(run(ValidationRunStatus.CREATED)));
        given(monthlyValidationJobTrigger.launch(any(), eq("req-1")))
                .willThrow(new java.util.concurrent.RejectedExecutionException("queue full"));

        assertThatThrownBy(() -> service.execute(100L, 1L, "req-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_005));
    }
}
