package com.susukkang.fgc.cap.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckRow;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CapCheckServiceImpl implements CapCheckService {

    private final CapCalculator capCalculator;
    private final CapCheckMapper capCheckMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public CapCheckSaveResult calculateAndSave(CapCalculationCommand command) {
        // 계산(CapCalculator)과 저장(capCheckMapper)을 하나의 트랜잭션으로 묶는다.
        // 계산 중 예외가 나면 당연히 저장도 안 되고, 저장이 실패해도 계산 결과가 반쪽만 남지 않는다.
        CapCalculationResult result = capCalculator.calculate(command);

        CapCheckInsertRow row = CapCheckInsertRow.builder()
                .validationRunId(command.validationRunId())
                .contractId(result.contractId())
                .paymentStage(result.paymentStage().name())
                .capRuleSetId(result.capRuleSetId())
                .refundRateTableId(result.refundRateTableId())
                .checkKind(result.checkKind().name())
                .asOfDate(result.asOfDate())
                .basePremiumAmount(result.basePremiumAmount())
                .refund12mAmount(result.refund12mAmount())
                .complianceDeductionAmount(result.complianceDeductionAmount())
                .limitAmount(result.limitAmount())
                .includedAmount(result.includedAmount())
                .remainingAmount(result.remainingAmount())
                .usagePct(result.usagePct())
                .resultStatus(result.resultStatus().name())
                .calculationSnapshotJson(writeJson(result.calculationSnapshot()))
                .build();
        capCheckMapper.insertCapCheck(row);

        if (!result.details().isEmpty()) {
            List<CapCheckDetailInsertRow> detailRows = new ArrayList<>(result.details().size());
            for (CapCheckDetailLine d : result.details()) {
                detailRows.add(CapCheckDetailInsertRow.builder()
                        .capCheckId(row.getCapCheckId())
                        .detailSeq(d.detailSeq())
                        .commissionItemId(d.commissionItemId())
                        .scheduleLineId(d.scheduleLineId())
                        .classificationSnapshot(d.classification())
                        .amount(d.amount())
                        .decisionReason(d.decisionReason())
                        .build());
            }
            capCheckMapper.insertCapCheckDetails(detailRows);
        }

        return new CapCheckSaveResult(row.getCapCheckId(), result);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CapCheckSaveResult> findLatest(Long contractId, PaymentStage paymentStage) {
        CapCheckRow row = capCheckMapper.findLatestByContractAndStage(contractId, paymentStage.name());
        if (row == null) {
            return Optional.empty();
        }

        List<CapCheckDetailLine> details = capCheckMapper.findDetailsByCapCheckId(row.getCapCheckId());

        CapCalculationResult result = new CapCalculationResult(
                row.getContractId(),
                PaymentStage.valueOf(row.getPaymentStage()),
                CapCheckKind.valueOf(row.getCheckKind()),
                row.getAsOfDate(),
                row.getCapRuleSetId(),
                row.getRefundRateTableId(),
                row.getBasePremiumAmount(),
                row.getRefund12mAmount(),
                row.getComplianceDeductionAmount(),
                row.getLimitAmount(),
                row.getIncludedAmount(),
                row.getRemainingAmount(),
                row.getUsagePct(),
                CapResultStatus.valueOf(row.getResultStatus()),
                details,
                readJson(row.getCalculationSnapshotJson())
        );

        return Optional.of(new CapCheckSaveResult(row.getCapCheckId(), result));
    }

    private String writeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
