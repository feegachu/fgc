package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapContractView;
import com.susukkang.fgc.cap.dto.CapRuleItemView;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.dto.ScheduleAmountView;
import com.susukkang.fgc.cap.mapper.CapContractMapper;
import com.susukkang.fgc.cap.mapper.CapRuleMapper;
import com.susukkang.fgc.cap.mapper.CapScheduleAmountMapper;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 설명 : 규제 정책과 증빙 실제액을 적용하는 수수료 한도 계산 구현체
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class CapCalculatorImpl implements CapCalculator {

    private static final String REFUND_ADDITION_STANDARD_DEDUCTION_80 = "STANDARD_DEDUCTION_80";
    private static final String INCLUDED = "INCLUDED";
    private static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";

    private final CapContractMapper capContractMapper;
    private final CapRuleMapper capRuleMapper;
    private final CapScheduleAmountMapper capScheduleAmountMapper;
    private final ProductRefundRateResolver refundRateResolver;

    @Override
    public CapCalculationResult calculate(CapCalculationCommand command) {
        // 계약 등록·수정 시(REALTIME) 또는 월 검증 배치(MONTHLY) 어느 쪽에서 호출돼도 아래 순서는 동일
        CapContractView contract = capContractMapper.findById(command.contractId());
        if (contract == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500,
                    Map.of("requestId", "contractId=" + command.contractId() + " not found"));
        }

        // "어떤 규칙을 적용할지"는 항상 계약 체결일 기준으로 찾음 (REG-19)
        CapRuleSetView ruleSet = capRuleMapper.findApplicableRuleSet(
                command.paymentStage().name(), contract.getContractDate(),
                contract.getInsurerId(), contract.getProductGroupCode(), contract.getChannelCode());
        if (ruleSet == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.CAP_004,
                    "paymentStage",
                    Map.of(
                            "contractId", command.contractId(),
                            "paymentStage", command.paymentStage().name()
                    ),
                    "적용 가능한 1,200% 룰셋이 없습니다."
            );
        }

        // 1) 기준 보험료 = 월납환산 초회보험료 원액(화면정의서 CAP-W01 "기준 보험료"/CAP-W02
        // "① 입력값 - 월납환산 초회보험료" = base_premium_amount). ×12 하지 않는다 — 그건
        // "한도"(② 계산식의 결과)지 "기준 보험료" 자체가 아니다.
        BigDecimal basePremiumAmount = contract.getMonthlyEquivalentFirstPremium();

        // 한도식의 기준액(월납 원액 × 배수, 기본 12). 저장·노출하지 않고 grossLimit 계산에만 쓴다.
        BigDecimal annualizedLimitBase = MoneyUtil.multiplyAndRound(
                basePremiumAmount, ruleSet.getPremiumMultiplier());

        // 2) 저해지·표준미달형 등 80% 이상 공제 대상이면 한도를 가산
        // 표준해약공제액의 80% 이상을 공제하는 상품은 12차월 예상 해약환급금만큼 한도가 더 큼 (REG-08)
        RefundAddition refundAddition = resolveRefundAddition(contract, ruleSet, annualizedLimitBase, command);

        // 2026-08-10 yslee - 준법경영비를 증빙 실제액 기준으로 제한
        // 기존 코드: 정책 공제율이 0보다 크면 월납 초회보험료의 3%를 일괄 공제
        // 문제: 실제 지급·증빙 금액이 없거나 상한보다 적어도 최대액을 자동 공제하여 REG-10 위반
        // 개선: 증빙 실제액과 정책상 최대 허용액 중 작은 금액만 공제하고 증빙 누락은 검토필요 처리
        BigDecimal grossLimit = annualizedLimitBase.add(refundAddition.amount());
        ComplianceDeduction complianceDeduction = resolveComplianceDeduction(
                basePremiumAmount,
                ruleSet.getComplianceDeductionPct(),
                command.complianceEvidenceAmount()
        );
        BigDecimal limitAmount = grossLimit.subtract(complianceDeduction.appliedAmount());

        // 4) 실제 산입액 집계
        // 계약월차 1~firstYearMonths(기본 12) 안에 있는 예상 schedule_line 한 줄씩 훑으면서,
        // 그 수수료 항목이 이 룰셋에서 INCLUDED/EXCLUDED/REVIEW_REQUIRED 중 무엇인지 붙임
        Map<Long, CapRuleItemView> ruleItemsByCommissionItem = new HashMap<>();
        for (CapRuleItemView item : capRuleMapper.findRuleItems(ruleSet.getCapRuleSetId())) {
            ruleItemsByCommissionItem.put(item.getCommissionItemId(), item);
        }
        List<ScheduleAmountView> scheduleAmounts = capScheduleAmountMapper.findFirstYearScheduleAmounts(
                command.contractId(), command.paymentStage().name(), ruleSet.getFirstYearMonths());

        List<CapCheckDetailLine> details = new ArrayList<>();
        BigDecimal includedAmount = BigDecimal.ZERO;
        // 환급률표를 못 찾은 경우(2단계)뿐 아니라, 산입 분류를 알 수 없는 항목이 하나라도 있으면
        // 전체 판정을 REVIEW_REQUIRED로 내림
        boolean anyReviewRequired = refundAddition.reviewRequired()
                || complianceDeduction.reviewRequired();
        int seq = 1;
        for (ScheduleAmountView line : scheduleAmounts) {
            CapRuleItemView ruleItem = ruleItemsByCommissionItem.get(line.getCommissionItemId());
            // 룰셋에 아예 등록되지 않은 수수료 항목은 자동으로 산입/제외를 판단하지 않고 REVIEW_REQUIRED로 둠
            //
            // 보류(TODO): cap_rule_item.evidence_required_yn(EXCLUDED 항목이 증빙을 요구하는지)을
            // 이 엔진이 읽지 않는다. 지금은 룰셋 템플릿의 정적 inclusion_status만으로 판정하고, 건별
            // 실제 증빙 유무는 확인하지 않음
            // 건별 증빙 연결은 transaction_attribution 경로가 아직 없어 미구현 상태
            String classification = ruleItem != null ? ruleItem.getInclusionStatus() : REVIEW_REQUIRED;
            String reason = ruleItem != null
                    ? ruleItem.getDecisionReason()
                    : "1,200% 룰셋에 분류되지 않은 수수료 항목이라 사람 판단이 필요하다";
            String itemCode = ruleItem != null ? ruleItem.getItemCode() : null;
            String itemName = ruleItem != null ? ruleItem.getItemName() : null;

            // schedule_line.expected_amount는 컬럼 자체가 numeric(15,2)라 이론상 원 미만 값을 담을 수
            // 있다(생성기가 정상 동작하면 항상 정수 won이겠지만, 그 보장을 이 엔진이 갖고 있지 않다).
            // MoneyUtil 규칙("각 지급행을 원 단위 HALF_UP으로 반올림한 뒤 합산")대로 여기서 먼저 반올림한다.
            BigDecimal amount = MoneyUtil.roundWon(line.getAmount());

            // evidenceRef는 스케줄 기반 산입 후보 시점에는 아직 존재하지 않는다(증빙 연결은 저장 이후 별도 절차) — null로 둔다
            details.add(new CapCheckDetailLine(seq++, line.getCommissionItemId(), itemCode, itemName,
                    line.getScheduleLineId(), line.getContractMonthNo(), classification, amount, reason, null));

            if (INCLUDED.equals(classification)) {
                includedAmount = includedAmount.add(amount);
            }
            if (REVIEW_REQUIRED.equals(classification)) {
                anyReviewRequired = true;
            }
        }

        // 5) 잔여 한도·사용률·최종 판정
        BigDecimal remainingAmount = limitAmount.subtract(includedAmount);
        BigDecimal usagePct = MoneyUtil.usagePercent(includedAmount, limitAmount);

        CapResultStatus resultStatus = determineResultStatus(
                anyReviewRequired, includedAmount, limitAmount, usagePct, ruleSet.getWarningUsagePct());

        // 6) 감사·재현용 스냅샷 구성 (저장은 CapCheckService 책임)
        Map<String, Object> snapshot = buildSnapshot(ruleSet, refundAddition, complianceDeduction);

        return new CapCalculationResult(
                command.contractId(), command.paymentStage(), command.checkKind(), command.asOfDate(),
                ruleSet.getCapRuleSetId(), refundAddition.refundRateTableId(),
                basePremiumAmount, refundAddition.amount(), complianceDeduction.appliedAmount(), limitAmount,
                includedAmount, remainingAmount, usagePct, resultStatus, details, snapshot);
    }

    // 80% 이상 공제 대상 상품이면 12차월 예상 해약환급금을 한도에 가산할 금액과, 그 판정에 쓴
    // 환급률표(refund_rate_table_id·policy_version_id·version_no)를 계산해서 돌려줌
    private RefundAddition resolveRefundAddition(CapContractView contract, CapRuleSetView ruleSet,
                                                   BigDecimal annualizedLimitBase, CapCalculationCommand command) {
        boolean applies = REFUND_ADDITION_STANDARD_DEDUCTION_80.equals(ruleSet.getRefundAdditionCondition())
                && Boolean.TRUE.equals(contract.getStandardDeduction80Yn());
        if (!applies) {
            return new RefundAddition(BigDecimal.ZERO, null, null, null, false);
        }

        // 반드시 계약 체결일(contract.getContractDate())로 조회
        RefundRateQuery query = new RefundRateQuery(contract.getInsurerId(), contract.getProductId(),
                contract.getPaymentTermMonths(), contract.getChannelCode(), contract.getContractDate());
        Optional<RefundRateResolution> resolution = refundRateResolver.resolve(query);
        if (resolution.isEmpty()) {
            return new RefundAddition(BigDecimal.ZERO, null, null, null, true);
        }

        RefundRateResolution r = resolution.get();
        BigDecimal amount = MoneyUtil.applyPercent(annualizedLimitBase, r.month12RatePct());
        return new RefundAddition(amount, r.refundRateTableId(), r.policyVersionId(), r.versionNo(), false);
    }

    private ComplianceDeduction resolveComplianceDeduction(
            BigDecimal basePremiumAmount,
            BigDecimal maximumPct,
            BigDecimal evidenceAmount
    ) {
        if (maximumPct == null || maximumPct.signum() <= 0) {
            return new ComplianceDeduction(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        BigDecimal maximumAmount = MoneyUtil.applyPercent(basePremiumAmount, maximumPct);
        if (evidenceAmount == null) {
            return new ComplianceDeduction(null, maximumAmount, BigDecimal.ZERO, true);
        }
        if (evidenceAmount.signum() < 0) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "complianceEvidenceAmount",
                    Map.of("field", "complianceEvidenceAmount"),
                    "준법경영비 증빙 금액은 0 이상이어야 합니다."
            );
        }
        BigDecimal verifiedAmount = MoneyUtil.roundWon(evidenceAmount);
        return new ComplianceDeduction(
                verifiedAmount,
                maximumAmount,
                verifiedAmount.min(maximumAmount),
                false
        );
    }

    private CapResultStatus determineResultStatus(boolean anyReviewRequired, BigDecimal includedAmount,
                                                    BigDecimal limitAmount, BigDecimal usagePct,
                                                    BigDecimal warningUsagePct) {
        if (anyReviewRequired) {
            return CapResultStatus.REVIEW_REQUIRED;
        }
        if (includedAmount.compareTo(limitAmount) > 0) {
            return CapResultStatus.VIOLATION;
        }
        if (usagePct.compareTo(warningUsagePct) >= 0) {
            return CapResultStatus.WARNING;
        }
        return CapResultStatus.NORMAL;
    }

    // 컬럼으로 뽑아내지 않은 "그때 어떤 정책값을 썼는지"를 감사·재현용으로 남김
    private Map<String, Object> buildSnapshot(
            CapRuleSetView ruleSet,
            RefundAddition refundAddition,
            ComplianceDeduction complianceDeduction
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("premiumMultiplier", ruleSet.getPremiumMultiplier());
        snapshot.put("refundAdditionCondition", ruleSet.getRefundAdditionCondition());
        snapshot.put("refundRateTablePolicyVersionId", refundAddition.policyVersionId());
        snapshot.put("refundRateTableVersionNo", refundAddition.versionNo());
        snapshot.put("complianceDeductionPct", ruleSet.getComplianceDeductionPct());
        snapshot.put("complianceEvidenceAmount", complianceDeduction.evidenceAmount());
        snapshot.put("complianceMaximumAmount", complianceDeduction.maximumAmount());
        snapshot.put("complianceAppliedAmount", complianceDeduction.appliedAmount());
        snapshot.put("warningUsagePct", ruleSet.getWarningUsagePct());
        return snapshot;
    }

    private record RefundAddition(BigDecimal amount, Long refundRateTableId, Long policyVersionId,
                                   Integer versionNo, boolean reviewRequired) {
    }

    private record ComplianceDeduction(
            BigDecimal evidenceAmount,
            BigDecimal maximumAmount,
            BigDecimal appliedAmount,
            boolean reviewRequired
    ) {
    }
}
