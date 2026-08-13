package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.ScheduleRegenerationPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import com.susukkang.fgc.validation.mapper.ValidationScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 월 통합검증 대상으로 선별된 계약의 예상 스케줄을 검증하고,
 * 누락되었거나 정책 버전이 변경된 스케줄을 기존 스케줄 도메인 서비스로 생성·재생성한다.
 */
@Service
@RequiredArgsConstructor
public class ValidationRunScheduleService implements ScheduleRegenerationPort {

    private final ValidationScheduleMapper validationScheduleMapper;
    private final ScheduleService scheduleService;
    private final ExceptionCaseMapper exceptionCaseMapper;

    @Override
    public StepProcessingResult regenerateSchedules(ValidationStepContext context) {
        return validateContractSchedules(context.validationRunId());
    }

    /**
     * 선별 계약의 활성 OPERATIONAL 헤더와 라인 구조를 검증하고 필요한 스케줄을 생성한다.
     * 실제 정책 선택과 버전 교체는 기존 {@link ScheduleService}가 담당한다.
     */
    @Transactional
    public StepProcessingResult validateContractSchedules(Long validationRunId) {
        if (validationRunId == null) {
            throw new IllegalArgumentException("검증 실행 ID가 없습니다.");
        }

        List<ValidationScheduleState> states =
                validationScheduleMapper.selectScheduleStates(validationRunId);
        Map<Long, List<ValidationScheduleState>> statesByContract = new LinkedHashMap<>();
        for (ValidationScheduleState state : states) {
            statesByContract.computeIfAbsent(state.getContractId(), ignored -> new ArrayList<>())
                    .add(state);
        }

        List<ContractSkip> skips = new ArrayList<>();
        for (Map.Entry<Long, List<ValidationScheduleState>> entry : statesByContract.entrySet()) {
            Long contractId = entry.getKey();
            String invalidReason = findStructuralError(entry.getValue());
            if (invalidReason != null) {
                exceptionCaseMapper.insertDataQualityCase(
                        validationRunId,
                        contractId,
                        "예상 스케줄 정합성 오류",
                        invalidReason
                );
                skips.add(new ContractSkip(contractId, "DATA_QUALITY", invalidReason));
                continue;
            }

            InsuranceContract contract = InsuranceContract.builder()
                    .contractId(contractId)
                    .build();
            scheduleService.generateSchedules(contract);
        }

        return new StepProcessingResult(statesByContract.size(), skips.size(), 0, skips);
    }

    private String findStructuralError(List<ValidationScheduleState> states) {
        for (ValidationScheduleState state : states) {
            int activeHeaderCount = valueOrZero(state.getActiveHeaderCount());
            if (activeHeaderCount > 1) {
                return state.getPaymentStage() + " 활성 OPERATIONAL 스케줄 헤더가 중복되었습니다.";
            }
            if (activeHeaderCount == 0) {
                continue;
            }
            if (state.getScheduleStatus() == ScheduleHeaderStatus.CANCELLED) {
                return state.getPaymentStage() + " 활성 스케줄 상태가 CANCELLED입니다.";
            }
            if (valueOrZero(state.getLineCount()) == 0) {
                return state.getPaymentStage() + " 활성 스케줄에 회차 라인이 없습니다.";
            }
            if (valueOrZero(state.getLineCount()) != valueOrZero(state.getDistinctLineCount())) {
                return state.getPaymentStage() + " 스케줄 회차 업무키가 중복되었습니다.";
            }
        }
        return null;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
