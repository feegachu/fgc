package com.susukkang.fgc.validation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.ValidationRunType;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ValidationRunCreateServiceImpl 단위테스트
 * ValidationRunMapper를 mock으로 대체해 "중복 체크 → run_no 채번 → INSERT" 흐름만 검증
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunCreateServiceImplTest {

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

        service = new ValidationRunCreateServiceImpl(validationRunMapper, policySnapshotMapper, new ObjectMapper());
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
        // MONTHLY가 아니면 existsActiveMonthlyRun을 아예 안 부르므로 스텁하지 않는다 —
        // 만약 구현이 이 메서드를 호출한다면 mock 기본값(false)이라 통과에 영향 없다.
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
}
