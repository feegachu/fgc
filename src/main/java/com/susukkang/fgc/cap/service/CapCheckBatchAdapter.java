package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.validation.batch.contract.CapCheckBatchPort;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.mapper.ValidationTargetSelectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : CapCheckBatchAdapter
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-14
 */
@RequiredArgsConstructor
@Service
public class CapCheckBatchAdapter implements CapCheckBatchPort {
    private final ValidationTargetSelectionMapper validationMapper;
    private final CapCheckMapper capCheckMapper;
    private final CapCheckBatchItemService itemService;
    private final CapCheckFailureRecordService failureRecordService;
    /**
     * 설명 : 배치 검증을 할 떄 들어오는 StepContext에서 계약 정보를 받아 1200%한도를 검증하는 메서드
     *
     * @param  context 각 Tasklet에서 입력받는 context
     * @return StepProcessingResult 1200%한도 검증 결과
     * @author hjKang
     * @since 2026-08-14
     */
    @Override
    public StepProcessingResult check(ValidationStepContext context, PaymentStage paymentStage) {
        /*
        → Mapper로 검증 대상 계약 목록 조회
        → 계약을 하나씩 1,200% 검증
        → 각 계약 결과를 DB에 저장v
        → 전체 처리 건수/실패 건수를 Tasklet에 반환
         */
        //Mapper로 검증 대상 계약 목록 조회
        Long validationRunId = context.validationRunId();

        LocalDate asOfDate = context.job()
                .validationMonth()
                .plusMonths(1)
                .minusDays(1);

        List<Long> contractIds =
                validationMapper.selectSelectedContractIds(validationRunId);

        long processedCount = 0;
        List<ContractSkip> skips = new ArrayList<>();

        for (Long contractId : contractIds) {
            try {
                BigDecimal complianceEvidenceAmount =
                        capCheckMapper.selectComplianceEvidenceAmount(
                                contractId,
                                paymentStage
                        );

                CapCalculationCommand command =
                        new CapCalculationCommand(
                                contractId,
                                paymentStage,
                                asOfDate,
                                CapCheckKind.MONTHLY,
                                validationRunId,
                                complianceEvidenceAmount
                        );

                itemService.process(command);
                // 해당 계약의 계산 및 저장 성공
                processedCount++;

            } catch (FgcBusinessException exception) {
                // 해당 계약만 실패 목록에 기록하고 다음 계약 진행

                failureRecordService.record(
                        validationRunId,
                        contractId,
                        paymentStage,
                        exception.getMessage()
                );
                skips.add(new ContractSkip(
                        contractId,
                        "CAP_CHECK_FAILED",
                        exception.getMessage()
                ));


            }
        }

        return new StepProcessingResult(
                processedCount,
                skips.size(),
                0,
                skips
        );
    }
}
