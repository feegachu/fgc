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

// 계산만 수행하고 저장은 안 함 — cap_check/cap_check_detail 저장과 트랜잭션 경계는
// 호출자(예: CapCheckService) 책임. 실시간 API(#3)와 월 배치(#4)가 "언제·어떤 단위로
// 커밋할지"를 다르게 관리해야 해서 분리함(배치는 청크 단위로 여러 건을 한 트랜잭션에 묶어야 함).
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
        // 계약없음·룰셋없음은 이 엔진을 정상 호출했다면 생기지 않아야 하는 시스템 상황이다
        // (호출자가 존재하는 계약 ID를 넘기고, cap_rule_set이 룰셋 데이터로 적용대상을 관리하기
        // 때문). 인터페이스정의서 3-2절 동결 오류코드 표에 이 상황 전용 코드가 없어 COMMON_500을 쓴다.
        CapContractView contract = capContractMapper.findById(command.contractId());
        if (contract == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500,
                    Map.of("requestId", "contractId=" + command.contractId() + " not found"));
        }

        // "어떤 규칙을 적용할지"는 항상 계약 체결일 기준으로 찾음 (REG-19)
        // cap_rule_set 이 계약일 범위로 정책을 구분해 두므로, 여기서는 "몇 년도 계약인지"를 몰라도 됨
        CapRuleSetView ruleSet = capRuleMapper.findApplicableRuleSet(
                command.paymentStage().name(), contract.getContractDate(),
                contract.getInsurerId(), contract.getProductGroupCode(), contract.getChannelCode());
        if (ruleSet == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500,
                    Map.of("requestId", "contractId=" + command.contractId()
                            + ", paymentStage=" + command.paymentStage() + " no applicable cap_rule_set"));
        }

        // 1) 기본 한도식
        // 초년도 모집수수료 한도 = 월납환산 초회보험료 × 12 (premium_multiplier 는 정책값이라
        // 자바 코드에 12를 고정하지 않고 cap_rule_set 에서 읽음 — COR-004)
        BigDecimal basePremiumAmount = MoneyUtil.multiplyAndRound(
                contract.getMonthlyEquivalentFirstPremium(), ruleSet.getPremiumMultiplier());

        // 2) 저해지·표준미달형 등 80% 이상 공제 대상이면 한도를 가산
        // 표준해약공제액의 80% 이상을 공제하는 상품은 12차월 예상 해약환급금만큼 한도가 더 큼 (REG-08)
        // 일반 상품(대부분)은 이 단계에서 그대로 0원이 더해짐
        RefundAddition refundAddition = resolveRefundAddition(contract, ruleSet, basePremiumAmount, command);

        // 3) 준법경영비 등 공제
        // 원수사→GA 단계에서만 준법경영비 3%를 뺀 금액이 실제 한도가 됨 (REG-10)
        // GA→FC 단계는 compliance_deduction_pct 가 항상 0이 되도록 DB CHECK로 강제돼 있어
        // 여기서 별도로 지급단계를 분기하지 않아도 자동으로 0원 공제가 됨
        BigDecimal grossLimit = basePremiumAmount.add(refundAddition.amount());
        BigDecimal complianceDeductionAmount = ruleSet.getComplianceDeductionPct().compareTo(BigDecimal.ZERO) > 0
                ? MoneyUtil.applyPercent(grossLimit, ruleSet.getComplianceDeductionPct())
                : BigDecimal.ZERO;
        BigDecimal limitAmount = grossLimit.subtract(complianceDeductionAmount);

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
        boolean anyReviewRequired = refundAddition.reviewRequired();
        int seq = 1;
        for (ScheduleAmountView line : scheduleAmounts) {
            CapRuleItemView ruleItem = ruleItemsByCommissionItem.get(line.getCommissionItemId());
            // 룰셋에 아예 등록되지 않은 수수료 항목은 자동으로 산입/제외를 판단하지 않고 REVIEW_REQUIRED로 둠
            String classification = ruleItem != null ? ruleItem.getInclusionStatus() : REVIEW_REQUIRED;
            String reason = ruleItem != null
                    ? ruleItem.getDecisionReason()
                    : "1,200% 룰셋에 분류되지 않은 수수료 항목이라 사람 판단이 필요하다";
            String itemCode = ruleItem != null ? ruleItem.getItemCode() : null;
            String itemName = ruleItem != null ? ruleItem.getItemName() : null;

            // evidenceRef는 스케줄 기반 산입 후보 시점에는 아직 존재하지 않는다(증빙 연결은 저장 이후 별도 절차) — null로 둔다
            details.add(new CapCheckDetailLine(seq++, line.getCommissionItemId(), itemCode, itemName,
                    line.getScheduleLineId(), line.getContractMonthNo(), classification, line.getAmount(), reason, null));

            if (INCLUDED.equals(classification)) {
                includedAmount = includedAmount.add(line.getAmount());
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
        Map<String, Object> snapshot = buildSnapshot(ruleSet, refundAddition);

        return new CapCalculationResult(
                command.contractId(), command.paymentStage(), command.checkKind(), command.asOfDate(),
                ruleSet.getCapRuleSetId(), refundAddition.refundRateTableId(),
                basePremiumAmount, refundAddition.amount(), complianceDeductionAmount, limitAmount,
                includedAmount, remainingAmount, usagePct, resultStatus, details, snapshot);
    }

    // 80% 이상 공제 대상 상품이면 12차월 예상 해약환급금을 한도에 가산할 금액과, 그 판정에 쓴
    // 환급률표(refund_rate_table_id·policy_version_id·version_no)를 계산해서 돌려줌.
    // 조건에 안 맞으면(일반 상품 대부분) 가산 없이 0원을 돌려줌 — 이게 기본 케이스임
    private RefundAddition resolveRefundAddition(CapContractView contract, CapRuleSetView ruleSet,
                                                   BigDecimal basePremiumAmount, CapCalculationCommand command) {
        boolean applies = REFUND_ADDITION_STANDARD_DEDUCTION_80.equals(ruleSet.getRefundAdditionCondition())
                && Boolean.TRUE.equals(contract.getStandardDeduction80Yn());
        if (!applies) {
            return new RefundAddition(BigDecimal.ZERO, null, null, null, false);
        }

        // 반드시 계약 체결일(contract.getContractDate())로 조회 — command.asOfDate() 를 쓰면 안 됨.
        // REALTIME 계산은 asOfDate 가 곧 계약일이라 차이가 없지만, MONTHLY 월 재검증은 asOfDate 가
        // 검증 실행월이라 계약일보다 한참 뒤일 수 있음. 그사이 새 환급률표 버전이 활성화됐다면
        // asOfDate 로 조회했을 때 "그때는 없던" 더 최신 표가 잘못 선택됨 (REG-19: 계약 체결일 기준)
        RefundRateQuery query = new RefundRateQuery(contract.getInsurerId(), contract.getProductId(),
                contract.getPaymentTermMonths(), contract.getChannelCode(), contract.getContractDate());
        Optional<RefundRateResolution> resolution = refundRateResolver.resolve(query);
        if (resolution.isEmpty()) {
            // 표(또는 12차월 값)가 없는 조합은 억지로 추정하지 않고 REVIEW_REQUIRED로 남김 (REG-23)
            return new RefundAddition(BigDecimal.ZERO, null, null, null, true);
        }

        RefundRateResolution r = resolution.get();
        BigDecimal amount = MoneyUtil.applyPercent(basePremiumAmount, r.month12RatePct());
        return new RefundAddition(amount, r.refundRateTableId(), r.policyVersionId(), r.versionNo(), false);
    }

    // 판정 우선순위(위에서부터 먼저 걸리는 조건이 최종 결과):
    // 1. REVIEW_REQUIRED — 룰셋 미분류 항목이나 환급률표 부재 등 사람 판단이 필요한 경우가 하나라도 있으면 최우선
    // 2. VIOLATION — 산입액이 한도를 실제로 초과
    // 3. WARNING — 아직 초과는 아니지만 사용률이 경고 기준(cap_rule_set.warning_usage_pct, 기본 90%) 이상
    // 4. NORMAL — 위 어디에도 해당하지 않는 정상 범위
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
    private Map<String, Object> buildSnapshot(CapRuleSetView ruleSet, RefundAddition refundAddition) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("premiumMultiplier", ruleSet.getPremiumMultiplier());
        snapshot.put("refundAdditionCondition", ruleSet.getRefundAdditionCondition());
        snapshot.put("refundRateTablePolicyVersionId", refundAddition.policyVersionId());
        snapshot.put("refundRateTableVersionNo", refundAddition.versionNo());
        snapshot.put("complianceDeductionPct", ruleSet.getComplianceDeductionPct());
        snapshot.put("warningUsagePct", ruleSet.getWarningUsagePct());
        return snapshot;
    }

    private record RefundAddition(BigDecimal amount, Long refundRateTableId, Long policyVersionId,
                                   Integer versionNo, boolean reviewRequired) {
    }
}
