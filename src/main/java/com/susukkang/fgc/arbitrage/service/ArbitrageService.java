package com.susukkang.fgc.arbitrage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.arbitrage.dto.*;
import com.susukkang.fgc.arbitrage.mapper.ArbitrageMapper;
import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.SurrenderValueSourceType;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설명 : 차익거래 검증 결과 조회 및 계약 단건 수동 검증 업무를 처리한다.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-08-12
 */
@Service
@RequiredArgsConstructor
public class ArbitrageService {
    private static final PaymentStage REGULATORY_PAYMENT_STAGE = PaymentStage.GA_TO_FC;
    // TODO 정책 파라미터 키와 적용 policy_version 선택 기준이 확정되면
    // policy_parameter에서 차익거래 해약환급금 합산 기간을 조회하도록 변경한다.
    // 현재 값은 REG-12의 계약 체결 후 3년(36개월) 기준을 따른다.
    private static final int REFUND_ADDITION_LAST_MONTH = 36;

    private final ArbitrageMapper arbitrageMapper;
    private final ValidationRunCreateService validationRunCreateService;
    private final ValidationRunMapper validationRunMapper;
    private final ExceptionCaseMapper exceptionCaseMapper;
    private final ObjectMapper objectMapper;

    /**
     * 설명 : 검색 조건에 해당하는 차익거래 검증 결과 요약과 페이징 목록을 조회한다.
     *
     * @param condition 검색 조건
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 차익거래 검증 결과 요약 및 목록
     * @author hjKang
     * @since 2026-08-12
     */
    public ArbitrageSearchResponse selectByCondition(
            ArbitrageCheckSearchCondition condition, int page, int size) {
        // 페이지 번호 유효성 검증
        if (page < 1) throw validationException("page", "page는 1 이상이어야 합니다.");

        // 페이지 크기 유효성 검증
        if (size < 1 || size > 100)
            throw validationException("size", "size는 1 이상 100 이하여야 합니다.");

        // 조회 시작 위치 계산 및 정수 범위 검증
        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE)
            throw validationException("page", "요청할 수 있는 페이지 범위를 초과했습니다.");
        int offset = (int) offsetLong;

        // 검색 조건에 해당하는 차익거래 검증 결과 목록 조회
        List<ArbitrageCheckView> arbitrageCheckList =
                arbitrageMapper.selectByCondition(condition, offset, size);
        // 검색 조건에 해당하는 판정별 요약 건수 조회
        ArbitrageCheckSummary summary = arbitrageMapper.arbitrageCheckSummary(condition);
        if (summary == null) summary = new ArbitrageCheckSummary();

        // 조회 목록을 페이지 응답으로 변환
        PageResponse<ArbitrageCheckView> items = PageResponse.of(
                arbitrageCheckList,
                page,
                size,
                summary.getTotalArbitrageChecks(),
                "arbitrageCheckId,desc"
        );

        // 요약 정보와 페이징 목록을 하나의 응답으로 반환
        return ArbitrageSearchResponse.builder().summary(summary).items(items).build();
    }

    /**
     * 설명 : 계약과 지급단계의 기준일별 차익거래 검증 결과 시계열을 조회한다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 기준일별 차익거래 검증 결과
     * @author hjKang
     * @since 2026-08-12
     */
    public List<ArbitrageCheckView> selectByContractId(
            Long contractId,
            PaymentStage paymentStage) {
        if (contractId == null || contractId < 1)
            throw validationException("contractId", "contractId는 1 이상이어야 합니다.");
        if (paymentStage == null)
            throw validationException("paymentStage", "지급단계는 필수입니다.");
        return arbitrageMapper.selectByContractId(contractId, paymentStage);
    }

    /**
     * 설명 : 계약 ID와 기준일을 기준으로 차익거래 수동 검증을 실행하고 결과를 저장한다.
     *
     * @param contractId 계약 ID
     * @param request 수동 검증 요청 정보
     * @param triggeredBy 실행 사용자 ID
     * @return 차익거래 수동 검증 결과
     * @author hjKang
     * @since 2026-08-12
     */
    @Transactional
    public ReArbitrageCheckResponse reArbitrageCheck(
            Long contractId,
            ReArbitrageCheckRequest request,
            Long triggeredBy) {
        // 서비스 직접 호출에서도 필수 입력값을 검증
        validateManualCheckInput(contractId, request, triggeredBy);

        // 수동 계약 검증 실행 생성 및 차익거래 단계로 진행
        ValidationRunRow run = validationRunCreateService.create(new CreateValidationRunCommand(
                request.getAsOfDate().withDayOfMonth(1),
                ValidationRunType.MANUAL_CONTRACT,
                triggeredBy
        ));
        if (validationRunMapper.transitionToRunning(run.getValidationRunId()) != 1)
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of());
        if (validationRunMapper.updateCurrentStep(run.getValidationRunId(), 5) != 1)
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of());

        ArbitrageCheckInsertDTO row = executeArbitrageCheck(
                run.getValidationRunId(), contractId, request);

        // 수동 검증 실행 완료 처리
        if (validationRunMapper.transitionToCompleted(run.getValidationRunId()) != 1)
            throw new FgcBusinessException(FgcErrorCode.VRUN_005, Map.of());

        // 생성된 검증 결과 ID와 판정 및 실행 ID 반환
        return ReArbitrageCheckResponse.builder()
                .arbitrageCheckId(row.getArbitrageCheckId())
                .resultStatus(row.getResultStatus())
                .validationRunId(run.getValidationRunId())
                .build();
    }

    /**
     * 설명 : 기존 월 검증 실행에 계약 단위 차익거래 결과를 계산하여 저장한다.
     *
     * @param validationRunId 검증 실행 ID
     * @param contractId 계약 ID
     * @param asOfDate 검증 기준일
     * @return 저장된 차익거래 검증 결과
     * @author hjKang
     * @since 2026-08-12
     */
    @Transactional
    public ArbitrageCheckInsertDTO checkInExistingRun(
            Long validationRunId,
            Long contractId,
            LocalDate asOfDate) {
        if (validationRunId == null || validationRunId < 1)
            throw validationException("validationRunId", "validationRunId는 1 이상이어야 합니다.");
        if (contractId == null || contractId < 1)
            throw validationException("contractId", "contractId는 1 이상이어야 합니다.");
        if (asOfDate == null)
            throw validationException("asOfDate", "검증 기준일은 필수입니다.");
        return executeArbitrageCheck(
                validationRunId,
                contractId,
                new ReArbitrageCheckRequest(asOfDate, "월 통합검증")
        );
    }

    private ArbitrageCheckInsertDTO executeArbitrageCheck(
            Long validationRunId,
            Long contractId,
            ReArbitrageCheckRequest request) {
        // 계약과 기준일 이하 최신 금융 스냅샷 조회
        ArbitrageCalculationSource source =
                arbitrageMapper.selectCalculationSource(contractId, request.getAsOfDate());
        if (source == null)
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("contractId", contractId));

        // 확정 지급·차감 순액과 활성 스케줄의 남은 지급예정액 조회
        ConfirmedCommissionSummary confirmedCommission = arbitrageMapper.sumConfirmedCommissionAmount(
                contractId, REGULATORY_PAYMENT_STAGE, request.getAsOfDate());
        BigDecimal confirmedPaymentAmount = confirmedCommission == null
                ? BigDecimal.ZERO : valueOrZero(confirmedCommission.getConfirmedPaymentAmount());
        BigDecimal confirmedDeductionAmount = confirmedCommission == null
                ? BigDecimal.ZERO : valueOrZero(confirmedCommission.getConfirmedDeductionAmount());
        BigDecimal paidCommissionAmount = confirmedCommission == null
                ? BigDecimal.ZERO : valueOrZero(confirmedCommission.getPaidCommissionAmount());
        BigDecimal plannedCommissionAmount = valueOrZero(arbitrageMapper.sumPlannedCommissionAmount(
                contractId, REGULATORY_PAYMENT_STAGE));

        // 금융자료 및 해약환급금 적용 조건 확인
        CalculationDecision decision = calculateDecision(
                source,
                paidCommissionAmount,
                plannedCommissionAmount,
                request
        );

        // 계산 결과와 재현 가능한 계산 근거 저장
        ArbitrageCheckInsertDTO row = ArbitrageCheckInsertDTO.builder()
                .validationRunId(validationRunId)
                .contractId(contractId)
                .paymentStage(REGULATORY_PAYMENT_STAGE)
                .asOfDate(request.getAsOfDate())
                .contractMonthNo(decision.contractMonthNo())
                .cumulativePaidPremium(decision.cumulativePaidPremium())
                .paidCommissionAmount(paidCommissionAmount)
                .plannedCommissionAmount(plannedCommissionAmount)
                .includedSurrenderValueAmount(decision.includedSurrenderValueAmount())
                .refundAdditionAppliedYn(decision.refundAdditionAppliedYn())
                .surrenderValueSourceType(decision.surrenderValueSourceType())
                .netDifferenceAmount(decision.projectedExcessAmount())
                .refundRateTableId(decision.refundRateTableId())
                .standardDeduction80Yn(Boolean.TRUE.equals(source.getStandardDeduction80Yn()))
                .resultStatus(decision.resultStatus())
                .calculationSnapshot(writeCalculationSnapshot(
                        source, confirmedPaymentAmount, confirmedDeductionAmount,
                        paidCommissionAmount, plannedCommissionAmount, decision, request))
                .build();
        int insertedRows = arbitrageMapper.insertArbitrageCheck(row);
        if (insertedRows != 1 || row.getArbitrageCheckId() == null)
            throw new FgcBusinessException(FgcErrorCode.COMMON_500, Map.of());

        // 차익거래 후보만 공통 예외 목록에 중복 없이 등록
        if (row.getResultStatus() == ArbitrageCheckStatus.CANDIDATE) {
            exceptionCaseMapper.insertArbitrageCandidate(
                    validationRunId,
                    contractId,
                    row.getArbitrageCheckId(),
                    REGULATORY_PAYMENT_STAGE.name(),
                    decision.reason()
            );
        }

        return row;
    }

    private CalculationDecision calculateDecision(
            ArbitrageCalculationSource source,
            BigDecimal paidCommissionAmount,
            BigDecimal plannedCommissionAmount,
            ReArbitrageCheckRequest request) {
        if (source.getCumulativePaidPremium() == null || source.getContractMonthNo() == null) {
            int monthNo = Math.max(1, Math.toIntExact(
                    ChronoUnit.MONTHS.between(
                            source.getContractDate().withDayOfMonth(1),
                            request.getAsOfDate().withDayOfMonth(1)) + 1));
            return reviewRequired(monthNo, "기준일 이하 계약 금융 스냅샷이 없습니다.");
        }

        int contractMonthNo = source.getContractMonthNo();
        BigDecimal cumulativePaidPremium = MoneyUtil.roundWon(source.getCumulativePaidPremium());
        RefundDecision refund = resolveSurrenderValue(source, cumulativePaidPremium);
        if (refund.reviewRequired())
            return reviewRequired(contractMonthNo, refund.reason(), cumulativePaidPremium);

        BigDecimal currentExcessAmount = maxZero(
                paidCommissionAmount
                        .add(refund.amount())
                        .subtract(cumulativePaidPremium));
        BigDecimal projectedExcessAmount = maxZero(
                paidCommissionAmount
                        .add(plannedCommissionAmount)
                        .add(refund.amount())
                        .subtract(cumulativePaidPremium));

        ArbitrageCheckStatus resultStatus = projectedExcessAmount.signum() > 0
                ? ArbitrageCheckStatus.CANDIDATE
                : ArbitrageCheckStatus.CLEAR;
        String candidateType = currentExcessAmount.signum() > 0
                ? "CURRENT_EXCESS"
                : projectedExcessAmount.signum() > 0 ? "PROJECTED_EXCESS" : "NONE";
        String reason = switch (candidateType) {
            case "CURRENT_EXCESS" -> "현재 확정 수수료와 해약환급금이 누적 납입보험료를 초과합니다.";
            case "PROJECTED_EXCESS" -> "지급예정 수수료를 포함하면 누적 납입보험료를 초과합니다.";
            default -> "현재 및 지급예정 수수료를 포함해도 누적 납입보험료를 초과하지 않습니다.";
        };

        return new CalculationDecision(
                contractMonthNo,
                cumulativePaidPremium,
                refund.amount(),
                refund.applied(),
                refund.sourceType(),
                refund.refundRateTableId(),
                currentExcessAmount,
                projectedExcessAmount,
                resultStatus,
                candidateType,
                reason
        );
    }

    private RefundDecision resolveSurrenderValue(
            ArbitrageCalculationSource source,
            BigDecimal cumulativePaidPremium) {
        boolean applies = Boolean.TRUE.equals(source.getStandardDeduction80Yn())
                && source.getContractMonthNo() >= 1
                && source.getContractMonthNo() <= REFUND_ADDITION_LAST_MONTH;
        if (!applies)
            return RefundDecision.notApplicable();

        if (source.getSurrenderValue() == null)
            return RefundDecision.reviewRequired("해약환급금 스냅샷 값이 없습니다.");

        if ("ACTUAL".equals(source.getSurrenderValueType())) {
            return new RefundDecision(
                    MoneyUtil.roundWon(source.getSurrenderValue()),
                    true,
                    SurrenderValueSourceType.ACTUAL,
                    source.getSnapshotRefundRateTableId(),
                    false,
                    null
            );
        }

        if (!"EXPECTED_TABLE".equals(source.getSurrenderValueType())
                && !"ESTIMATED".equals(source.getSurrenderValueType()))
            return RefundDecision.reviewRequired("해약환급금 출처 유형이 올바르지 않습니다.");
        if (source.getSnapshotRefundRateTableId() == null)
            return RefundDecision.reviewRequired("예상 해약환급금의 환급률표 ID가 없습니다.");

        List<ArbitrageRefundRateCandidate> candidates =
                arbitrageMapper.selectRefundRateCandidates(source, source.getContractDate());
        if (candidates.size() != 1)
            return RefundDecision.reviewRequired(candidates.isEmpty()
                    ? "적용 가능한 예상 해약환급률표가 없습니다."
                    : "적용 가능한 예상 해약환급률표가 여러 건입니다.");

        ArbitrageRefundRateCandidate candidate = candidates.get(0);
        if (!source.getSnapshotRefundRateTableId().equals(candidate.getRefundRateTableId()))
            return RefundDecision.reviewRequired("금융 스냅샷과 적용 환급률표가 일치하지 않습니다.");
        return new RefundDecision(
                MoneyUtil.roundWon(source.getSurrenderValue()),
                true,
                SurrenderValueSourceType.EXPECTED_TABLE,
                candidate.getRefundRateTableId(),
                false,
                null
        );
    }

    private CalculationDecision reviewRequired(int contractMonthNo, String reason) {
        return reviewRequired(contractMonthNo, reason, BigDecimal.ZERO);
    }

    private CalculationDecision reviewRequired(
            int contractMonthNo,
            String reason,
            BigDecimal cumulativePaidPremium) {
        return new CalculationDecision(
                contractMonthNo,
                cumulativePaidPremium,
                BigDecimal.ZERO,
                false,
                SurrenderValueSourceType.NOT_APPLICABLE,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                ArbitrageCheckStatus.REVIEW_REQUIRED,
                "DATA_REVIEW_REQUIRED",
                reason
        );
    }

    private String writeCalculationSnapshot(
            ArbitrageCalculationSource source,
            BigDecimal confirmedPaymentAmount,
            BigDecimal confirmedDeductionAmount,
            BigDecimal paidCommissionAmount,
            BigDecimal plannedCommissionAmount,
            CalculationDecision decision,
            ReArbitrageCheckRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("manualReason", request.getReason());
        snapshot.put("snapshotAsOfDate", source.getSnapshotAsOfDate());
        snapshot.put("cumulativePaidPremium", decision.cumulativePaidPremium());
        snapshot.put("confirmedPaymentAmount", confirmedPaymentAmount);
        snapshot.put("confirmedDeductionAmount", confirmedDeductionAmount);
        snapshot.put("paidCommissionAmount", paidCommissionAmount);
        snapshot.put("plannedCommissionAmount", plannedCommissionAmount);
        snapshot.put("includedSurrenderValueAmount", decision.includedSurrenderValueAmount());
        snapshot.put("currentExcessAmount", decision.currentExcessAmount());
        snapshot.put("projectedExcessAmount", decision.projectedExcessAmount());
        snapshot.put("candidateType", decision.candidateType());
        snapshot.put("decisionReason", decision.reason());
        snapshot.put("refundRateTableId", decision.refundRateTableId());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500, Map.of());
        }
    }

    private void validateManualCheckInput(
            Long contractId,
            ReArbitrageCheckRequest request,
            Long triggeredBy) {
        if (contractId == null || contractId < 1)
            throw validationException("contractId", "contractId는 1 이상이어야 합니다.");
        if (request == null)
            throw validationException("request", "차익거래 검증 요청 정보가 없습니다.");
        if (request.getAsOfDate() == null)
            throw validationException("asOfDate", "검증 기준일은 필수입니다.");
        if (request.getReason() == null || request.getReason().isBlank())
            throw validationException("reason", "수동 검증 사유는 필수입니다.");
        if (request.getReason().length() > 200)
            throw validationException("reason", "수동 검증 사유는 200자 이하여야 합니다.");
        if (triggeredBy == null || triggeredBy < 1)
            throw validationException("triggeredBy", "실행 사용자 정보가 올바르지 않습니다.");
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : MoneyUtil.roundWon(value);
    }

    private BigDecimal maxZero(BigDecimal value) {
        return value.max(BigDecimal.ZERO);
    }

    private FgcBusinessException validationException(String field, String detail) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                detail
        );
    }

    private record RefundDecision(
            BigDecimal amount,
            boolean applied,
            SurrenderValueSourceType sourceType,
            Long refundRateTableId,
            boolean reviewRequired,
            String reason) {
        private static RefundDecision notApplicable() {
            return new RefundDecision(
                    BigDecimal.ZERO,
                    false,
                    SurrenderValueSourceType.NOT_APPLICABLE,
                    null,
                    false,
                    null
            );
        }

        private static RefundDecision reviewRequired(String reason) {
            return new RefundDecision(
                    BigDecimal.ZERO,
                    false,
                    SurrenderValueSourceType.NOT_APPLICABLE,
                    null,
                    true,
                    reason
            );
        }
    }

    private record CalculationDecision(
            int contractMonthNo,
            BigDecimal cumulativePaidPremium,
            BigDecimal includedSurrenderValueAmount,
            boolean refundAdditionAppliedYn,
            SurrenderValueSourceType surrenderValueSourceType,
            Long refundRateTableId,
            BigDecimal currentExcessAmount,
            BigDecimal projectedExcessAmount,
            ArbitrageCheckStatus resultStatus,
            String candidateType,
            String reason) {
    }
}
