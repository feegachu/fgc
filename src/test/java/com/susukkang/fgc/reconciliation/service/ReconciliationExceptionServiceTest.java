package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReconciliationExceptionServiceTest {

    @Mock
    private ReconciliationRunMapper reconciliationRunMapper;

    @Mock
    private ExceptionCaseMapper exceptionCaseMapper;

    private ReconciliationExceptionService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationExceptionService(reconciliationRunMapper, exceptionCaseMapper);
    }

    @Test
    void createdAndSkippedDuplicateAddUpToCandidateCount() {
        given(reconciliationRunMapper.findById(41L)).willReturn(runLinkedToValidationRun(41L, 900L));
        ReconciliationExceptionBulkCreateRow row = new ReconciliationExceptionBulkCreateRow();
        row.setCandidateCount(5L);
        row.setCreatedCount(2L);
        given(exceptionCaseMapper.bulkCreateFromReconciliationResultsByRun(41L)).willReturn(row);

        ReconciliationExceptionBulkCreateResponse response = service.bulkCreate(41L);

        assertThat(response.created()).isEqualTo(2L);
        assertThat(response.skippedDuplicate()).isEqualTo(3L);
    }

    @Test
    void throwsCommon004WhenReconciliationRunDoesNotExist() {
        given(reconciliationRunMapper.findById(99L)).willReturn(null);

        assertThatThrownBy(() -> service.bulkCreate(99L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception ->
                        assertThat(((FgcBusinessException) exception).getErrorCode())
                                .isEqualTo(FgcErrorCode.COMMON_004));
        verify(exceptionCaseMapper, never()).bulkCreateFromReconciliationResultsByRun(99L);
    }

    // fgc.record_exception_detection()이 validation_run_id를 필수로 요구해서(V23_1),
    // 월 검증 실행과 연결되지 않은 단독 대사 실행은 Mapper까지 가지 않고 여기서 막아야 한다
    // (안 막으면 Mapper의 INNER JOIN 때문에 "후보 0건"으로 조용히 넘어가 버린다).
    @Test
    void throwsReco003WhenReconciliationRunHasNoLinkedValidationRun() {
        ReconciliationRunRow standaloneRun = new ReconciliationRunRow();
        standaloneRun.setReconciliationRunId(41L);
        standaloneRun.setValidationRunId(null);
        given(reconciliationRunMapper.findById(41L)).willReturn(standaloneRun);

        assertThatThrownBy(() -> service.bulkCreate(41L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception ->
                        assertThat(((FgcBusinessException) exception).getErrorCode())
                                .isEqualTo(FgcErrorCode.RECO_003));
        verify(exceptionCaseMapper, never()).bulkCreateFromReconciliationResultsByRun(41L);
    }

    private static ReconciliationRunRow runLinkedToValidationRun(Long reconciliationRunId, Long validationRunId) {
        ReconciliationRunRow row = new ReconciliationRunRow();
        row.setReconciliationRunId(reconciliationRunId);
        row.setValidationRunId(validationRunId);
        return row;
    }
}
