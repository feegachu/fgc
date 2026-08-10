package com.susukkang.fgc.validation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunInsertRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.PolicySnapshotMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ValidationRunCreateServiceImpl 단위테스트
 * ValidationRunMapper를 mock으로 대체해 "중복 체크 → run_no 채번 → INSERT" 흐름만 검증
 *
 * NoOpTransactionManager: create()가 이제 TransactionTemplate(REQUIRES_NEW)로 재시도
 * 트랜잭션을 직접 여는 구조라, 진짜 DataSource 없이도 TransactionTemplate.execute()가
 * 동작하려면 PlatformTransactionManager가 하나 있어야 한다. 여기서는 실제로 커밋·롤백할
 * 대상이 없으므로(ValidationRunMapper 자체가 mock) 아무 것도 안 하는 최소 구현을 쓴다 —
 * ValidationRunMapperIntegrationTest가 실제 DB·실제 트랜잭션 매니저로 이 부분까지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunCreateServiceImplTest {

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Mock
    private ValidationRunMapper validationRunMapper;

    @Mock
    private PolicySnapshotMapper policySnapshotMapper;

    private ValidationRunCreateServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(policySnapshotMapper.findActiveCapRuleSets(any()))
                .thenReturn(List.of());
        lenient().when(policySnapshotMapper.findActiveRefundRateTables(any()))
                .thenReturn(List.of());
        lenient().when(policySnapshotMapper.findActiveProductOfferings(any()))
                .thenReturn(List.of());

        service = new ValidationRunCreateServiceImpl(
                validationRunMapper, policySnapshotMapper, new ObjectMapper(),
                new ConstraintErrorCodeResolver(), new NoOpTransactionManager());
    }

    // insert가 useGeneratedKeys로 row.validationRunId를 채우는 것을 mock에서 흉내낸다.
    // 실제 동작은 ValidationRunMapperIntegrationTest#insertGeneratesIdAndAppliesDefaults가 증명한다.
    private void stubSuccessfulInsert(Long generatedId, String status) {
        doAnswer(invocation -> {
            ValidationRunInsertRow insertedRow = invocation.getArgument(0);
            insertedRow.setValidationRunId(generatedId);
            return null;
        }).when(validationRunMapper).insert(any());

        ValidationRunRow found = new ValidationRunRow();
        found.setValidationRunId(generatedId);
        found.setStatus(status);
        when(validationRunMapper.findById(generatedId)).thenReturn(found);
    }

    @Test
    // MONTHLY + 동일 월 활성 실행 있음 → VRUN_001, insert는 호출 안 됨
    void throwsAlreadyRunningForDuplicateActiveMonthlyRun() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunMapper.existsActiveMonthlyRun(month)).thenReturn(true);

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 1L);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_001);

        verify(validationRunMapper, never()).insert(any());
    }

    @Test
    // MANUAL_CONTRACT/PRE_CONFIRM은 활성 MONTHLY 실행이 있어도 통과해야 함
    void allowsNonMonthlyRunTypeEvenWhenMonthlyRunIsActive() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        // MONTHLY가 아니면 existsActiveMonthlyRun을 아예 안 부르므로 스텁 X
        when(validationRunMapper.findNextRunNo(month)).thenReturn(1);
        stubSuccessfulInsert(100L, "CREATED");

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MANUAL_CONTRACT, 1L);

        ValidationRunRow result = service.create(command);

        assertThat(result.getStatus()).isEqualTo("CREATED");
        verify(validationRunMapper, never()).existsActiveMonthlyRun(any());
    }

    @Test
    // 정상 생성 — findNextRunNo 결과가 insert에 그대로 전달되는지, 반환된 행의 status가 CREATED인지
    void createsRunWithNextRunNo() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunMapper.existsActiveMonthlyRun(month)).thenReturn(false);
        when(validationRunMapper.findNextRunNo(month)).thenReturn(3);
        stubSuccessfulInsert(100L, "CREATED");

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 42L);

        ValidationRunRow result = service.create(command);

        ArgumentCaptor<ValidationRunInsertRow> captor = ArgumentCaptor.forClass(ValidationRunInsertRow.class);
        verify(validationRunMapper).insert(captor.capture());

        assertThat(captor.getValue().getRunNo()).isEqualTo(3);
        assertThat(captor.getValue().getValidationMonth()).isEqualTo(month);
        assertThat(captor.getValue().getRunType()).isEqualTo("MONTHLY");
        assertThat(captor.getValue().getTriggeredBy()).isEqualTo(42L);
        assertThat(result.getStatus()).isEqualTo("CREATED");
    }

    @Test
    // MonthlyValidationJob은 JobParameters의 runNo와 validation_run.run_no가 반드시 같아야 한다.
    void createsRunWithExplicitRunNoForBatch() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunMapper.existsActiveMonthlyRun(month)).thenReturn(false);
        stubSuccessfulInsert(100L, "CREATED");

        ValidationRunRow result = service.create(
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 42L, 7));

        ArgumentCaptor<ValidationRunInsertRow> captor = ArgumentCaptor.forClass(ValidationRunInsertRow.class);
        verify(validationRunMapper).insert(captor.capture());
        verify(validationRunMapper, never()).findNextRunNo(month);

        assertThat(captor.getValue().getRunNo()).isEqualTo(7);
        assertThat(result.getStatus()).isEqualTo("CREATED");
    }

    @Test
    // run_no 채번 경합(uq_validation_run 위반)은 활성 월 중복이 아니므로 재시도해서 결국 성공해야 한다
    void retriesOnRunNoCollisionAndSucceeds() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunMapper.existsActiveMonthlyRun(month)).thenReturn(false);
        // 1차 시도(run_no=3)는 경쟁자와 충돌, 2차 시도(run_no=4)는 성공한다고 가정.
        when(validationRunMapper.findNextRunNo(month)).thenReturn(3, 4);
        doThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_validation_run\""))
                .doAnswer(invocation -> {
                    ValidationRunInsertRow insertedRow = invocation.getArgument(0);
                    insertedRow.setValidationRunId(100L);
                    return null;
                })
                .when(validationRunMapper).insert(any());

        ValidationRunRow found = new ValidationRunRow();
        found.setValidationRunId(100L);
        found.setStatus("CREATED");
        when(validationRunMapper.findById(100L)).thenReturn(found);

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 1L);

        ValidationRunRow result = service.create(command);

        assertThat(result.getStatus()).isEqualTo("CREATED");
        verify(validationRunMapper, times(2)).insert(any());
        verify(validationRunMapper, times(2)).findNextRunNo(month);
    }

    @Test
    // 활성 MONTHLY 중복(uq_validation_run_active_month 위반)은 run_no 문제가 아니라 재시도해도
    // 해결되지 않는다 — 재시도 없이 그대로 던져야 한다(GlobalExceptionHandler가 VRUN_001/409로 변환)
    void doesNotRetryOnActiveMonthlyRunConflict() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunMapper.existsActiveMonthlyRun(month)).thenReturn(false);
        when(validationRunMapper.findNextRunNo(month)).thenReturn(1);
        doThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_validation_run_active_month\""))
                .when(validationRunMapper).insert(any());

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 1L);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(validationRunMapper, times(1)).insert(any());
    }
}
