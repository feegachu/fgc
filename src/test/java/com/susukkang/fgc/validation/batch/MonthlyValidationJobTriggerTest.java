package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.time.LocalDate;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MonthlyValidationJobTrigger — JobParameters가 행 값 그대로 구성되는지(멱등 계약,
 * 배치_Step_협업계약 §7-4)와 기동 직전 상태 재확인(중복 클릭 봉쇄)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MonthlyValidationJobTriggerTest {

    @Mock
    private Job monthlyValidationJob;

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private ValidationRunMapper validationRunMapper;

    @InjectMocks
    private MonthlyValidationJobTrigger trigger;

    @AfterEach
    void shutdownExecutor() {
        trigger.shutdown();
    }

    private ValidationRunRow row(String status) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(100L);
        row.setValidationMonth(LocalDate.of(2026, 7, 1));
        row.setRunNo(2);
        row.setRunType("MONTHLY");
        row.setStatus(status);
        row.setTriggeredBy(7L);
        return row;
    }

    @Test
    void buildsJobParametersFromRowValues() throws Exception {
        ValidationRunRow created = row("CREATED");
        given(validationRunMapper.findById(100L)).willReturn(created);
        given(jobLauncher.run(eq(monthlyValidationJob), any())).willReturn(mock(JobExecution.class));

        trigger.launch(created, "20260817-abc123").join();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(monthlyValidationJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString("validationMonth")).isEqualTo("2026-07");
        assertThat(params.getLong("runNo")).isEqualTo(2L);
        assertThat(params.getString("runType")).isEqualTo("MONTHLY");
        assertThat(params.getLong("triggeredBy")).isEqualTo(7L);
        assertThat(params.getString("requestId")).isEqualTo("20260817-abc123");
    }

    @Test
    void skipsLaunchWhenRunIsNoLongerCreated() throws Exception {
        ValidationRunRow created = row("CREATED");
        // API guard 통과 후 executor 태스크가 돌기 전에 다른 요청이 먼저 기동한 상황
        given(validationRunMapper.findById(100L)).willReturn(row("RUNNING"));

        assertThatThrownBy(() -> trigger.launch(created, "req-1").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(jobLauncher, never()).run(any(), any());
    }
}
