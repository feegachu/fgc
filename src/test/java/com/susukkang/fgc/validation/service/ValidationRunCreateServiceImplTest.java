package com.susukkang.fgc.validation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.base.repository.ProductOfferingRepository;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.repository.CapRuleSetRepository;
import com.susukkang.fgc.policy.repository.RefundRateTableRepository;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ValidationRunCreateServiceImpl 단위테스트
 * ValidationRunRepository/CapRuleSetRepository/RefundRateTableRepository/ProductOfferingRepository를
 * mock으로 대체해 "중복 체크 → run_no 채번 → save" 흐름만 검증
 *
 * NoOpTransactionManager: create()가 이제 TransactionTemplate(REQUIRES_NEW)로 재시도
 * 트랜잭션을 직접 여는 구조라, 진짜 DataSource 없이도 TransactionTemplate.execute()가
 * 동작하려면 PlatformTransactionManager가 하나 있어야 한다. 여기서는 실제로 커밋·롤백할
 * 대상이 없으므로(Repository 자체가 mock) 아무 것도 안 하는 최소 구현을 쓴다 —
 * ValidationRunRepositoryIntegrationTest가 실제 DB·실제 트랜잭션 매니저로 이 부분까지 검증한다.
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
    private ValidationRunRepository validationRunRepository;

    @Mock
    private CapRuleSetRepository capRuleSetRepository;

    @Mock
    private RefundRateTableRepository refundRateTableRepository;

    @Mock
    private ProductOfferingRepository productOfferingRepository;

    private ValidationRunCreateServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(capRuleSetRepository.findActiveCapRuleSets(any())).thenReturn(List.of());
        lenient().when(refundRateTableRepository.findActiveRefundRateTables(any())).thenReturn(List.of());
        lenient().when(productOfferingRepository.findActiveProductOfferings(any())).thenReturn(List.of());

        service = new ValidationRunCreateServiceImpl(
                validationRunRepository, capRuleSetRepository, refundRateTableRepository,
                productOfferingRepository, new ObjectMapper(),
                new ConstraintErrorCodeResolver(), new NoOpTransactionManager());
    }

    // save()가 IDENTITY로 validationRunId를 채우는 것을 mock에서 흉내낸다.
    // 실제 동작은 ValidationRunRepositoryIntegrationTest#saveGeneratesIdAndAppliesBuilderDefaults가 증명한다.
    private void stubSuccessfulSave(Long generatedId) {
        when(validationRunRepository.save(any())).thenAnswer(invocation -> {
            ValidationRun run = invocation.getArgument(0);
            ReflectionTestUtils.setField(run, "validationRunId", generatedId);
            return run;
        });
    }

    private ValidationRun existingRun(Long id, ValidationRunStatus status, String runType, Long triggeredBy) {
        ValidationRun run = ValidationRun.builder()
                .validationMonth(LocalDate.of(2026, 8, 1))
                .runNo(7)
                .runType(runType)
                .triggeredBy(triggeredBy)
                .build();
        ReflectionTestUtils.setField(run, "validationRunId", id);
        ReflectionTestUtils.setField(run, "status", status);
        return run;
    }

    @Test
    // MONTHLY + 동일 월 활성 실행 있음 → VRUN_001, save는 호출 안 됨
    void throwsAlreadyRunningForDuplicateActiveMonthlyRun() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(eq(month), eq("MONTHLY"), anyList()))
                .thenReturn(true);

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 1L);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_001);

        verify(validationRunRepository, never()).save(any());
    }

    @Test
    // MANUAL_CONTRACT/PRE_CONFIRM은 활성 MONTHLY 실행이 있어도 통과해야 함
    void allowsNonMonthlyRunTypeEvenWhenMonthlyRunIsActive() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        // MONTHLY가 아니면 existsByValidationMonthAndRunTypeAndStatusIn을 아예 안 부르므로 스텁 X
        when(validationRunRepository.findNextRunNo(month)).thenReturn(1);
        stubSuccessfulSave(100L);

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MANUAL_CONTRACT, 1L);

        ValidationRunRow result = service.create(command);

        assertThat(result.getStatus()).isEqualTo("CREATED");
        verify(validationRunRepository, never())
                .existsByValidationMonthAndRunTypeAndStatusIn(any(), any(), anyList());
    }

    @Test
    // 정상 생성 — findNextRunNo 결과가 save에 그대로 전달되는지, 반환된 행의 status가 CREATED인지
    void createsRunWithNextRunNo() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(eq(month), eq("MONTHLY"), anyList()))
                .thenReturn(false);
        when(validationRunRepository.findNextRunNo(month)).thenReturn(3);
        stubSuccessfulSave(100L);

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 42L);

        ValidationRunRow result = service.create(command);

        ArgumentCaptor<ValidationRun> captor = ArgumentCaptor.forClass(ValidationRun.class);
        verify(validationRunRepository).save(captor.capture());

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
        when(validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(eq(month), eq("MONTHLY"), anyList()))
                .thenReturn(false);
        stubSuccessfulSave(100L);

        ValidationRunRow result = service.create(
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 42L, 7));

        ArgumentCaptor<ValidationRun> captor = ArgumentCaptor.forClass(ValidationRun.class);
        verify(validationRunRepository).save(captor.capture());
        verify(validationRunRepository, never()).findNextRunNo(month);

        assertThat(captor.getValue().getRunNo()).isEqualTo(7);
        assertThat(result.getStatus()).isEqualTo("CREATED");
    }

    @Test
    // 재시작 시나리오: INSERT는 이미 커밋됐는데 그 이후(Step 완료 기록 등)에서 중단된 뒤
    // 같은 (validationMonth, runNo)로 create()를 다시 부르면, 새 INSERT를 시도하지 않고
    // 기존 행을 그대로 재사용해야 한다(멱등성).
    void reusesExistingRunOnRestartWithSameExplicitRunNo() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        ValidationRun existing = existingRun(100L, ValidationRunStatus.RUNNING, "MONTHLY", 42L);
        when(validationRunRepository.findByValidationMonthAndRunNo(month, 7)).thenReturn(Optional.of(existing));

        ValidationRunRow result = service.create(
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 42L, 7));

        assertThat(result.getValidationRunId()).isEqualTo(100L);
        assertThat(result.getStatus()).isEqualTo("RUNNING");
        verify(validationRunRepository, never()).save(any());
        verify(validationRunRepository, never())
                .existsByValidationMonthAndRunTypeAndStatusIn(any(), any(), anyList());
    }

    @Test
    // 같은 (validationMonth, runNo)에 이미 다른 요청(runType 또는 triggeredBy가 다른) 행이
    // 있으면 재시작이 아니라 진짜 충돌이므로 VRUN_005로 막아야 한다
    void throwsStateConflictWhenExistingRunDoesNotMatchTheRequest() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        ValidationRun existing = existingRun(100L, ValidationRunStatus.RUNNING, "MONTHLY", 999L);
        when(validationRunRepository.findByValidationMonthAndRunNo(month, 7)).thenReturn(Optional.of(existing));

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 42L, 7);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);

        verify(validationRunRepository, never()).save(any());
    }

    @Test
    // run_no 채번 경합(uq_validation_run 위반)은 활성 월 중복이 아니므로 재시도해서 결국 성공해야 한다
    void retriesOnRunNoCollisionAndSucceeds() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(eq(month), eq("MONTHLY"), anyList()))
                .thenReturn(false);
        // 1차 시도(run_no=3)는 경쟁자와 충돌, 2차 시도(run_no=4)는 성공한다고 가정.
        when(validationRunRepository.findNextRunNo(month)).thenReturn(3, 4);
        when(validationRunRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_validation_run\""))
                .thenAnswer(invocation -> {
                    ValidationRun run = invocation.getArgument(0);
                    ReflectionTestUtils.setField(run, "validationRunId", 100L);
                    return run;
                });

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 1L);

        ValidationRunRow result = service.create(command);

        assertThat(result.getStatus()).isEqualTo("CREATED");
        verify(validationRunRepository, times(2)).save(any());
        verify(validationRunRepository, times(2)).findNextRunNo(month);
    }

    @Test
    // 활성 MONTHLY 중복(uq_validation_run_active_month 위반)은 run_no 문제가 아니라 재시도해도
    // 해결되지 않는다 — 재시도 없이 그대로 던져야 한다(GlobalExceptionHandler가 VRUN_001/409로 변환)
    void doesNotRetryOnActiveMonthlyRunConflict() {
        LocalDate month = LocalDate.of(2026, 8, 1);
        when(validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(eq(month), eq("MONTHLY"), anyList()))
                .thenReturn(false);
        when(validationRunRepository.findNextRunNo(month)).thenReturn(1);
        when(validationRunRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_validation_run_active_month\""));

        CreateValidationRunCommand command =
                new CreateValidationRunCommand(month, ValidationRunType.MONTHLY, 1L);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(validationRunRepository, times(1)).save(any());
    }
}
