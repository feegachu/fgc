package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunCommand;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequestPort;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 대사 실행 생성 서비스 단위 테스트
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationRunServiceImplTest {

    @Mock
    private ReconciliationRunMapper reconciliationRunMapper;

    @Mock
    private ValidationRunMapper validationRunMapper;

    @Mock
    private ReconciliationRunLifecycleService lifecycleService;

    @Mock
    private ReconciliationExecutionRequestPort executionRequestPort;

    private ReconciliationRunServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationRunServiceImpl(
                reconciliationRunMapper,
                validationRunMapper,
                lifecycleService,
                Optional.of(executionRequestPort)
        );
    }

    @Test
    void 참조를_검증하고_CREATED에서_RUNNING으로_전이한다() {
        CreateReconciliationRunCommand command = command();
        given(reconciliationRunMapper.existsActiveInsurer(3L)).willReturn(true);
        given(validationRunMapper.findById(17L)).willReturn(validationRun(LocalDate.of(2026, 8, 1)));
        doAnswer(invocation -> {
            ReconciliationRunInsertRow row = invocation.getArgument(0);
            row.setReconciliationRunId(29L);
            return null;
        }).when(reconciliationRunMapper).insert(any());
        given(reconciliationRunMapper.findById(29L)).willReturn(reconciliationRun(29L));

        ReconciliationRunRow result = service.create(command);

        assertThat(result.getReconciliationRunId()).isEqualTo(29L);
        assertThat(result.getStatus()).isEqualTo("RUNNING");
        verify(lifecycleService).start(29L);
        verify(executionRequestPort).requestExecution(any(ReconciliationExecutionRequest.class));
    }

    @Test
    void 동일_자연키_실행이_있으면_RECO_002로_거절한다() {
        given(reconciliationRunMapper.existsActiveInsurer(3L)).willReturn(true);
        given(validationRunMapper.findById(17L)).willReturn(validationRun(LocalDate.of(2026, 8, 1)));
        given(reconciliationRunMapper.findByNaturalKey(
                LocalDate.of(2026, 8, 1), PaymentStage.GA_TO_FC, 3L, 17L
        )).willReturn(reconciliationRun(29L));

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.RECO_002);
                    assertThat(exception.getParams()).containsEntry("reconciliationRunId", 29L);
                });

        verify(reconciliationRunMapper, never()).insert(any());
        verify(executionRequestPort, never()).requestExecution(any());
    }

    @Test
    void 실행기가_연결되지_않으면_행을_생성하지_않는다() {
        ReconciliationRunServiceImpl unavailableService = new ReconciliationRunServiceImpl(
                reconciliationRunMapper,
                validationRunMapper,
                lifecycleService,
                Optional.empty()
        );
        given(reconciliationRunMapper.existsActiveInsurer(3L)).willReturn(true);
        given(validationRunMapper.findById(17L)).willReturn(validationRun(LocalDate.of(2026, 8, 1)));

        assertThatThrownBy(() -> unavailableService.create(command()))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_500));

        verify(reconciliationRunMapper, never()).insert(any());
    }

    @Test
    void 실행요청_등록이_실패하면_생성결과를_반환하지_않는다() {
        given(reconciliationRunMapper.existsActiveInsurer(3L)).willReturn(true);
        given(validationRunMapper.findById(17L)).willReturn(validationRun(LocalDate.of(2026, 8, 1)));
        doAnswer(invocation -> {
            ReconciliationRunInsertRow row = invocation.getArgument(0);
            row.setReconciliationRunId(29L);
            return null;
        }).when(reconciliationRunMapper).insert(any());
        doThrow(new IllegalStateException("실행 요청 거절"))
                .when(executionRequestPort).requestExecution(any());

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실행 요청 거절");

        verify(lifecycleService).start(29L);
        verify(reconciliationRunMapper, never()).findById(29L);
    }

    @Test
    void 존재하지_않는_보험회사는_404로_거절한다() {
        given(reconciliationRunMapper.existsActiveInsurer(3L)).willReturn(false);

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004);
                    assertThat(exception.getField()).isEqualTo("insurerId");
                });

        verify(reconciliationRunMapper, never()).insert(any());
    }

    @Test
    void 검증실행의_기준월이_다르면_400으로_거절한다() {
        given(reconciliationRunMapper.existsActiveInsurer(3L)).willReturn(true);
        given(validationRunMapper.findById(17L)).willReturn(validationRun(LocalDate.of(2026, 7, 1)));

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002);
                    assertThat(exception.getField()).isEqualTo("validationRunId");
                });

        verify(reconciliationRunMapper, never()).insert(any());
    }

    private static CreateReconciliationRunCommand command() {
        return new CreateReconciliationRunCommand(
                LocalDate.of(2026, 8, 1),
                PaymentStage.GA_TO_FC,
                3L,
                17L,
                5L
        );
    }

    private static ValidationRunRow validationRun(LocalDate month) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(17L);
        row.setValidationMonth(month);
        return row;
    }

    private static ReconciliationRunRow reconciliationRun(Long id) {
        ReconciliationRunRow row = new ReconciliationRunRow();
        row.setReconciliationRunId(id);
        row.setStatus("RUNNING");
        return row;
    }
}
