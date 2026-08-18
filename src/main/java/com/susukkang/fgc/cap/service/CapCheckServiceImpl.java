package com.susukkang.fgc.cap.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapAgentSummaryRow;
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
import com.susukkang.fgc.cap.dto.CapStageSummaryRow;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
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
    // 계약 생성·수정 흐름은 CAP_004를 잡아 계약은 보존하고 검토 케이스만 남긴다.
    // REQUIRED 참여 트랜잭션에서 이 예외를 rollback-only로 표시하면, 호출부가 예외를
    // 흡수해도 최종 커밋이 UnexpectedRollbackException으로 실패한다. 다른 업무 예외는
    // 호출한 상위 서비스까지 전파되어 그 상위 트랜잭션의 기본 롤백 규칙을 그대로 따른다.
    @Transactional(noRollbackFor = FgcBusinessException.class)
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

        // insertCapCheckDetails가 (cap_check_id, detail_seq) 기준 upsert라 자리별로 최신값을
        // 덮어써 주지만, 이번 재계산이 이전보다 항목 수가 "줄었을" 경우 그 초과분은 upsert만으로
        // 안 지워진다 — 그 뒷자리만 잘라낸다("지웠다 다시 넣기"가 아니라 꼬리 정리, §7-6
        // 공통규칙 1 준수, 코드리뷰 반영 2026-08-11). 새로 INSERT된 행(재사용이 아님)이면 어차피
        // 지울 뒷자리가 없어 안전한 no-op이다.
        int maxDetailSeq = result.details().stream().mapToInt(CapCheckDetailLine::detailSeq).max().orElse(0);
        capCheckMapper.pruneCapCheckDetails(row.getCapCheckId(), maxDetailSeq);

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
                criteria.insurerId(), criteria.organizationId(), criteria.contractNo(), offset, size);
        long total = capCheckMapper.count(
                criteria.month(), criteria.paymentStage(), criteria.resultStatus(),
                criteria.insurerId(), criteria.organizationId(), criteria.contractNo());
        CapCheckSummary summary = CapCheckSummary.from(capCheckMapper.summarize(
                criteria.month(), criteria.paymentStage(), criteria.insurerId(),
                criteria.organizationId(), criteria.contractNo()));
        List<CapStageSummaryRow> stageSummary = completeStageSummary(capCheckMapper.summarizeByStage(
                criteria.month(), criteria.insurerId(), criteria.organizationId(), criteria.contractNo()));
        List<CapAgentSummaryRow> agentSummary = capCheckMapper.summarizeByAgent(
                criteria.month(), criteria.insurerId(), criteria.organizationId(), criteria.contractNo());

        PageResponse<CapCheckListRow> pageResponse =
                PageResponse.of(rows, page, size, total, "asOfDate,desc");
        return new CapCheckSearchResult(summary, stageSummary, agentSummary, pageResponse);
    }

    private List<CapStageSummaryRow> completeStageSummary(List<CapStageSummaryRow> rows) {
        Map<PaymentStage, CapStageSummaryRow> byStage = new EnumMap<>(PaymentStage.class);
        for (CapStageSummaryRow row : rows) {
            byStage.put(PaymentStage.valueOf(row.getPaymentStage()), row);
        }
        return List.of(
                byStage.getOrDefault(PaymentStage.INSURER_TO_GA, emptyStage(PaymentStage.INSURER_TO_GA)),
                byStage.getOrDefault(PaymentStage.GA_TO_FC, emptyStage(PaymentStage.GA_TO_FC)));
    }

    private CapStageSummaryRow emptyStage(PaymentStage paymentStage) {
        CapStageSummaryRow row = new CapStageSummaryRow();
        row.setPaymentStage(paymentStage.name());
        row.setLimitAmountTotal(BigDecimal.ZERO);
        row.setIncludedAmountTotal(BigDecimal.ZERO);
        row.setComplianceDeductionAmountTotal(BigDecimal.ZERO);
        row.setUsagePct(BigDecimal.ZERO.setScale(6));
        return row;
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
