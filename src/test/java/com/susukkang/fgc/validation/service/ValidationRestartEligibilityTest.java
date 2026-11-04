package com.susukkang.fgc.validation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** FGC-FUN-045: 생성·진행·성공·확정 상태는 실패 재시작 대상이 아니다. */
@ExtendWith(MockitoExtension.class)
class ValidationRestartEligibilityTest {
  @Mock ValidationRunRepository runs;
  @Mock JdbcTemplate jdbc;
  @InjectMocks ValidationRestartService service;

  @ParameterizedTest
  @EnumSource(
      value = ValidationRunStatus.class,
      names = {"CREATED", "RUNNING", "COMPLETED", "FINALIZED"})
  void refusesNonFailedRuns(ValidationRunStatus status) {
    ValidationRun run = org.mockito.Mockito.mock(ValidationRun.class);
    when(run.getStatus()).thenReturn(status);
    when(runs.findById(1L)).thenReturn(Optional.of(run));
    assertThat(service.eligibility(1L).allowed()).isFalse();
  }
}
