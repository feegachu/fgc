package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateResponse;
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
        given(reconciliationRunMapper.findById(41L)).willReturn(new ReconciliationRunRow());
        given(exceptionCaseMapper.countReconciliationMismatchCandidates(41L)).willReturn(5L);
        given(exceptionCaseMapper.insertFromReconciliationResultsByRun(41L)).willReturn(2L);

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
        verify(exceptionCaseMapper, never()).insertFromReconciliationResultsByRun(99L);
    }
}
