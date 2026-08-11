package com.susukkang.fgc.cap.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;
import com.susukkang.fgc.cap.dto.CapCheckDetailInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapCheckInsertRow;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckRow;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.cap.dto.CapCheckSummary;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
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

    // IF-API-30 페이징 계약(1-base page, size 1~100). 컨트롤러뿐 아니라 이 서비스를 부르는 어떤
    // 호출자든 이 한도를 강제해야 해서 서비스 계층에서 직접 막는다
    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

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
                        .itemCode(d.itemCode())
                        .itemName(d.itemName())
                        .scheduleLineId(d.scheduleLineId())
                        .contractMonthNo(d.contractMonthNo())
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
        return Optional.ofNullable(row).map(this::toSaveResult);
    }

    @Override
    @Transactional(readOnly = true)
    public CapCheckSearchResult search(CapCheckSearchCriteria criteria, int page, int size) {
        if (page < MIN_PAGE) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "page", Map.of("field", "page"), null);
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "size", Map.of("field", "size"), null);
        }

        // size는 100 이하로 막혀 있지만 page는 위쪽 한도가 없어 (page-1)*size가 int 범위를 넘길 수
        // 있다 — long으로 먼저 계산해 오버플로를 걸러낸 뒤에만 매퍼로 넘긴다.
        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "page", Map.of("field", "page"), null);
        }
        int offset = (int) offsetLong;

        List<CapCheckListRow> rows = capCheckMapper.search(
                criteria.month(), criteria.paymentStage(), criteria.resultStatus(),
                criteria.insurerId(), criteria.contractNo(), offset, size);
        long total = capCheckMapper.count(
                criteria.month(), criteria.paymentStage(), criteria.resultStatus(),
                criteria.insurerId(), criteria.contractNo());
        CapCheckSummary summary = CapCheckSummary.from(capCheckMapper.summarize(
                criteria.month(), criteria.paymentStage(), criteria.insurerId(), criteria.contractNo()));

        PageResponse<CapCheckListRow> pageResponse =
                PageResponse.of(rows, page, size, total, "asOfDate,desc");
        return new CapCheckSearchResult(summary, pageResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CapCheckBasisResponse> findDetail(Long capCheckId) {
        CapCheckRow row = capCheckMapper.findById(capCheckId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(CapCheckBasisResponse.from(toSaveResult(row), row.getContractNo()));
    }

    private CapCheckSaveResult toSaveResult(CapCheckRow row) {
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

        return new CapCheckSaveResult(row.getCapCheckId(), result);
    }

    private String writeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // "{}"로 조용히 넘기면 근거(계산 스냅샷) 없는 cap_check가 성공한 것처럼 저장된다.
            // calculateAndSave는 @Transactional이라 여기서 던지면 INSERT까지 통째로 롤백된다.
            throw new FgcBusinessException(FgcErrorCode.COMMON_500,
                    Map.of("requestId", "calculation_snapshot 직렬화 실패: " + e.getMessage()));
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
            // 빈 맵으로 감추면 저장된 판정이 근거 없이 조회되는 것처럼 보인다 — 데이터가 깨졌다는
            // 사실을 그대로 드러내야 원인 파악이 된다.
            throw new FgcBusinessException(FgcErrorCode.COMMON_500,
                    Map.of("requestId", "calculation_snapshot 역직렬화 실패: " + e.getMessage()));
        }
    }
}
