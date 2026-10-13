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
import com.susukkang.fgc.cap.repository.CapContractQueryRepository;
import com.susukkang.fgc.cap.repository.CapRuleQueryRepository;
import com.susukkang.fgc.cap.repository.CapScheduleAmountQueryRepository;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.ProtectionType;
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
    private static final String EXCLUDED = "EXCLUDED";
    private static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final CapContractQueryRepository capContractQueryRepository;
    private final CapRuleQueryRepository capRuleQueryRepository;
    private final CapScheduleAmountQueryRepository capScheduleAmountQueryRepository;
    private final ProductRefundRateResolver refundRateResolver;

    @Override
    public CapCalculationResult calculate(CapCalculationCommand command) {
        // 계약 등록·수정 시(REALTIME) 또는 월 검증 배치(MONTHLY) 어느 쪽에서 호출돼도 아래 순서는 동일
        CapContractView contract = capContractQueryRepository.findCapViewByContractId(command.contractId());
        if (contract == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500,
                    null, Map.of(), "contractId=" + command.contractId() + " not found");
        }

        // 2026-10-13 #477 - 보장성보험만 1,200% 규제 대상이다 (감독규정 제4-32조⑤, FAQ 2차 Q11)
        // 기존 코드: protection_type 을 읽지 않아 저축성·일반손해·자동차 계약도 룰셋을 찾아 판정했다.
        // 문제: 규정상 한도가 없는 계약이 위반·검토필요가 되고, 룰셋이 없으면 CAP_004 예외가 쌓였다.
        // 개선: 룰셋 조회 전에 거른다. 조회 SQL 에 조건을 넣으면 "룰셋 없음"으로 보여 예외가 생기므로 여기서 처리한다.
        if (!ProtectionType.isRegulated(contract.getProtectionType())) {
            return notApplicable(command, contract);
        }

        // "어떤 규칙을 적용할지"는 항상 계약 체결일 기준으로 찾음 (REG-19)
        CapRuleSetView ruleSet = capRuleQueryRepository.findApplicableRuleSet(
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

        // 한도식의 기준액(월납 원액 × 배수, 기본 12). 저장하지 않고 한도·주의 판정에만 쓴다.
        BigDecimal annualizedLimitBase = MoneyUtil.multiplyAndRound(
                basePremiumAmount, ruleSet.getPremiumMultiplier());

        // 2) 저해지·표준미달형 등 80% 이상 공제 대상이면 12차월 예상 해약환급금을 구함 (REG-08)
        RefundAddition refundAddition = resolveRefundAddition(contract, ruleSet, annualizedLimitBase, command);

        // 2026-08-10 yslee - 준법경영비를 증빙 실제액 기준으로 제한
        // 기존 코드: 정책 공제율이 0보다 크면 월납 초회보험료의 3%를 일괄 공제
        // 문제: 실제 지급·증빙 금액이 없거나 상한보다 적어도 최대액을 자동 공제하여 REG-10 위반
        // 개선: 증빙 실제액과 정책상 최대 허용액 중 작은 금액만 공제하고 증빙 누락은 검토필요 처리
        ComplianceDeduction complianceDeduction = resolveComplianceDeduction(
                basePremiumAmount,
                ruleSet.getComplianceDeductionPct(),
                command.complianceEvidenceAmount()
        );

        // 2026-10-11 yslee - 해약환급금·준법경영비 반영 방향을 규정대로 교정 (#468)
        // 기존 코드: 한도 = 월납×12 + 해약환급금 − 준법경영비
        // 문제: 감독규정 제4-32조⑤·FAQ Q1-1은 해약환급금을 수수료등에 "합산"하고(산입 + 환급금 ≤ 1,200%),
        //       ⑭는 준법경영비를 수수료등 계산에서 "제외"한다 — 부호가 둘 다 반대라 환급금 계약은 위반을 놓치고
        //       준법경영비 증빙 계약은 정상을 위반으로 판정했다
        // 개선: 산입 + 환급금 − 준법경영비 ≤ 월납×12 를 한도 쪽으로 이항한다.
        //       판정은 FAQ 식과 같고, 지급확정(CapValidator)·정합성 검사(산입액 = 상세 합계)는 이 한도를 그대로 쓴다.
        //       환급률 100% 초과 환급률표는 resolveRefundAddition이 REVIEW_REQUIRED로 올리고,
        //       max(0)은 cap_check.limit_amount >= 0 제약을 지키는 마지막 방어선이다.
        BigDecimal limitAmount = annualizedLimitBase
                .subtract(refundAddition.amount())
                .add(complianceDeduction.appliedAmount())
                .max(BigDecimal.ZERO);

        // 4) 실제 산입액 집계
        // 계약월차 1~firstYearMonths(기본 12) 안에 있는 예상 schedule_line 한 줄씩 훑으면서,
        // 그 수수료 항목이 이 룰셋에서 INCLUDED/EXCLUDED/REVIEW_REQUIRED 중 무엇인지 붙임
        Map<Long, CapRuleItemView> ruleItemsByCommissionItem = new HashMap<>();
        for (CapRuleItemView item : capRuleQueryRepository.findRuleItems(ruleSet.getCapRuleSetId())) {
            ruleItemsByCommissionItem.put(item.getCommissionItemId(), item);
        }
        List<ScheduleAmountView> scheduleAmounts = capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(
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
            // 룰셋에 없는 항목과 증빙 필수 제외항목의 미연결 건은 자동 제외하지 않는다.
            // 후자는 transaction_attribution에 연결된 확정 증빙이 있을 때만 EXCLUDED를 유지한다.
            boolean evidenceMissing = ruleItem != null
                    && EXCLUDED.equals(ruleItem.getInclusionStatus())
                    && ruleItem.isEvidenceRequiredYn()
                    && (line.getEvidenceRef() == null || line.getEvidenceRef().isBlank());
            String classification = ruleItem == null || evidenceMissing
                    ? REVIEW_REQUIRED
                    : ruleItem.getInclusionStatus();
            String reason = ruleItem != null
                    ? evidenceMissing
                        ? "제외 요건의 건별 증빙이 연결되지 않아 사람 판단이 필요하다"
                        : ruleItem.getDecisionReason()
                    : "1,200% 룰셋에 분류되지 않은 수수료 항목이라 사람 판단이 필요하다";
            String itemCode = ruleItem != null ? ruleItem.getItemCode() : null;
            String itemName = ruleItem != null ? ruleItem.getItemName() : null;

            // schedule_line.expected_amount는 컬럼 자체가 numeric(15,2)라 이론상 원 미만 값을 담을 수
            // 있다(생성기가 정상 동작하면 항상 정수 won이겠지만, 그 보장을 이 엔진이 갖고 있지 않다).
            // MoneyUtil 규칙("각 지급행을 원 단위 HALF_UP으로 반올림한 뒤 합산")대로 여기서 먼저 반올림한다.
            BigDecimal amount = MoneyUtil.roundWon(line.getAmount());

            details.add(new CapCheckDetailLine(seq++, line.getCommissionItemId(), itemCode, itemName,
                    line.getScheduleLineId(), line.getContractMonthNo(), classification, amount, reason,
                    line.getEvidenceRef()));

            if (INCLUDED.equals(classification)) {
                includedAmount = includedAmount.add(amount);
            }
            if (REVIEW_REQUIRED.equals(classification)) {
                anyReviewRequired = true;
            }
        }

        // 5) 잔여 한도·사용률·최종 판정
        BigDecimal remainingAmount = limitAmount.subtract(includedAmount);
        // 한도 0에 산입액이 있으면 100% — CapValidatorImpl·지급확정 경로와 같은 규칙
        BigDecimal usagePct = limitAmount.signum() == 0 && includedAmount.signum() > 0
                ? ONE_HUNDRED
                : MoneyUtil.usagePercent(includedAmount, limitAmount);

        CapResultStatus resultStatus = anyReviewRequired
                ? CapResultStatus.REVIEW_REQUIRED
                : CapResultStatus.judge(remainingAmount, annualizedLimitBase, ruleSet.getWarningUsagePct());

        // 6) 감사·재현용 스냅샷 구성 (저장은 CapCheckService 책임)
        Map<String, Object> snapshot = buildSnapshot(ruleSet, refundAddition, complianceDeduction);

        return new CapCalculationResult(
                command.contractId(), command.paymentStage(), command.checkKind(), command.asOfDate(),
                ruleSet.getCapRuleSetId(), refundAddition.refundRateTableId(),
                basePremiumAmount, refundAddition.amount(), complianceDeduction.appliedAmount(), limitAmount,
                includedAmount, remainingAmount, usagePct, resultStatus, details, snapshot);
    }

    // 보장성이 아닌 계약의 결과 — 한도·산입액은 0, 룰셋·환급률표는 연결하지 않고 사유를 스냅샷에 남긴다
    private CapCalculationResult notApplicable(CapCalculationCommand command, CapContractView contract) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("notApplicable", true);
        snapshot.put("notApplicableReason", "보장성보험이 아니라 1,200% 규제 대상이 아니다 (감독규정 제4-32조⑤)");
        snapshot.put("protectionType", contract.getProtectionType());
        return new CapCalculationResult(
                command.contractId(), command.paymentStage(), command.checkKind(), command.asOfDate(),
                null, null,
                contract.getMonthlyEquivalentFirstPremium(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, CapResultStatus.NOT_APPLICABLE, List.of(), snapshot);
    }

    // 80% 이상 공제 대상 상품이면 수수료등에 합산할(= 한도에서 뺄) 12차월 예상 해약환급금과, 그 판정에 쓴
    // 환급률표(refund_rate_table_id·policy_version_id·version_no)를 계산해서 돌려줌
    private RefundAddition resolveRefundAddition(CapContractView contract, CapRuleSetView ruleSet,
                                                   BigDecimal annualizedLimitBase, CapCalculationCommand command) {
        boolean applies = REFUND_ADDITION_STANDARD_DEDUCTION_80.equals(ruleSet.getRefundAdditionCondition())
                && Boolean.TRUE.equals(contract.getStandardDeduction80Yn());
        if (!applies) {
            return new RefundAddition(BigDecimal.ZERO, null, null, null, false, false);
        }

        // 반드시 계약 체결일(contract.getContractDate())로 조회
        RefundRateQuery query = new RefundRateQuery(contract.getInsurerId(), contract.getProductId(),
                contract.getPaymentTermMonths(), contract.getChannelCode(), contract.getContractDate());
        Optional<RefundRateResolution> resolution = refundRateResolver.resolve(query);
        if (resolution.isEmpty()) {
            return new RefundAddition(BigDecimal.ZERO, null, null, null, true, false);
        }

        RefundRateResolution r = resolution.get();
        BigDecimal amount = MoneyUtil.applyPercent(annualizedLimitBase, r.month12RatePct());
        // 1년 경과 예상 해약환급금이 1년 납입보험료를 넘는 환급률표는 데이터 오류다.
        // 숫자를 조용히 자르지 않고 표가 없을 때와 같이 사람 판단으로 넘긴다.
        boolean abnormalRate = r.month12RatePct().compareTo(ONE_HUNDRED) > 0;
        return new RefundAddition(amount, r.refundRateTableId(), r.policyVersionId(), r.versionNo(),
                abnormalRate, abnormalRate);
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

    // 컬럼으로 뽑아내지 않은 "그때 어떤 정책값을 썼는지"를 감사·재현용으로 남김
    private Map<String, Object> buildSnapshot(
            CapRuleSetView ruleSet,
            RefundAddition refundAddition,
            ComplianceDeduction complianceDeduction
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("premiumMultiplier", ruleSet.getPremiumMultiplier());
        snapshot.put("refundAdditionCondition", ruleSet.getRefundAdditionCondition());
        snapshot.put("refundTableMissing", refundAddition.reviewRequired() && !refundAddition.abnormalRate());
        snapshot.put("refundRateAbnormal", refundAddition.abnormalRate());
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
                                   Integer versionNo, boolean reviewRequired, boolean abnormalRate) {
    }

    private record ComplianceDeduction(
            BigDecimal evidenceAmount,
            BigDecimal maximumAmount,
            BigDecimal appliedAmount,
            boolean reviewRequired
    ) {
    }
}
