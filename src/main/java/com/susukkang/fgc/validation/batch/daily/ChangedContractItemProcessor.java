package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.mapper.ContractStatusEventProcessingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;

import java.time.LocalDate;
import java.util.List;

/**
 * changedContractStep의 Processor: 계약 1건을 다시 검증
 *
 * 대상 검증(1차) : "1,200% 누적 · 차익거래 · 원장 불균형 재검사"(IF-BAT-02)
 * 스케줄 재생성은 무조건 호출X - "예상 지급 스케줄은 계약·정책·상태 변경으로 재생성이 필요한 경우에만 영향 범위에서 호출합니다" (IF-BAT-02)
 */
@RequiredArgsConstructor
public class ChangedContractItemProcessor implements ItemProcessor<Long, ChangedContractResult> {

    private final ContractMapper contractMapper;
    private final ScheduleService scheduleService;
    private final CapCheckService capCheckService;
    private final ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;

    @Override
    public ChangedContractResult process(Long contractId) {
        try {
            InsuranceContract contract = contractMapper.selectById(contractId);
            if (contract == null) {
                // Reader가 목록을 뽑은 시점과 Processor가 실제로 조회하는 시점 사이에 계약이
                // 삭제될 일은 이 도메인에서는 없지만(계약은 논리 삭제/상태변경만 함), 방어적으로
                // 데이터 품질 스킵으로 처리한다.
                return ChangedContractResult.dataQualitySkip(contractId, "계약을 찾을 수 없음: " + contractId);
            }

            List<Long> pendingEventIds = contractStatusEventProcessingMapper.findPendingEventIds(
                    contractId, DailyChangedContractJobNames.JOB_NAME);
            if (!pendingEventIds.isEmpty()) {
                scheduleService.generateSchedules(contract);
            }

            LocalDate asOfDate = LocalDate.now(DateUtil.SEOUL_ZONE);
            for (PaymentStage paymentStage : PaymentStage.values()) {
                capCheckService.calculateAndSave(
                        CapCalculationCommand.realtime(contractId, paymentStage, asOfDate));
            }

            return ChangedContractResult.success(contractId);
        } catch (FgcBusinessException e) {
            return ChangedContractResult.dataQualitySkip(contractId, describe(e));
        }
    }

    private String describe(FgcBusinessException e) {
        String code = e.getErrorCode().getCode();
        return e.getDetail() == null ? code : code + ": " + e.getDetail();
    }
}
