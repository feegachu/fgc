package com.susukkang.fgc.acquisitioncost.service;

import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckRequest;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostBasis;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostPolicyRef;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckResponse.AcquisitionCostReason;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckSearchCondition;
import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostResultStatus;
import com.susukkang.fgc.acquisitioncost.repository.AcquisitionCostCheckRepository;
import com.susukkang.fgc.acquisitioncost.repository.AcquisitionCostCheckRepository.ContractFacts;
import com.susukkang.fgc.acquisitioncost.repository.AcquisitionCostCheckRepository.Parameter;
import com.susukkang.fgc.acquisitioncost.repository.AcquisitionCostCheckRepository.ScheduleRef;
import com.susukkang.fgc.acquisitioncost.service.ContractAcquisitionCostValidator.Line;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.repository.ContractLockRepository;
import com.susukkang.fgc.policy.code.AcquisitionCostParameter;
import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;
import com.susukkang.fgc.policy.dto.FeeRegimeRulesetSelection;
import com.susukkang.fgc.policy.service.FeeRegimeRulesetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 설명 : FUN-062 계약체결비용 한도 검증 요청과 결과 조회. 계약 기준 예정 스케줄을 읽어 판정하고 결과를 쌓는다.
 * 회차·지급·실행의 상태는 바꾸지 않으며 예정 생성(014·037·038)은 이 서비스를 호출하지 않는다.
 * 자료가 부족하면 값을 지어내지 않고 금액 null과 REVIEW_REQUIRED로 저장한다.
 */
@Service
@RequiredArgsConstructor
public class AcquisitionCostCheckService {
    /** 산식이 바뀌면 올린다. 저장한 결과가 어느 산식으로 나왔는지 남는다. */
    static final String CALC_RULE_VERSION = "acq-cost-v1";
    private static final String AUDIT_ACTION = "ACQUISITION_COST_CHECKED";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AcquisitionCostCheckRepository store;
    private final FeeRegimeRulesetService rulesets;
    private final ContractLockRepository contractLock;
    private final AuditLogService auditLogService;

    @Transactional
    public AcquisitionCostCheckResponse request(AcquisitionCostCheckRequest request, String idempotencyKey, long userId) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw invalid("Idempotency-Key");
        }
        long contractId = request.contractId();
        String hash = hash(contractId, request.paymentStage());
        if (!store.tryLockRequest(idempotencyKey)) throw new FgcBusinessException(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS);
        var prior = store.findRequest(idempotencyKey);
        if (prior.isPresent()) {
            var stored = prior.get();
            if (stored.requestedBy() != userId || !stored.requestHash().equals(hash)) {
                throw new FgcBusinessException(FgcErrorCode.IMPL_IDEMPOTENCY_CONFLICT);
            }
            return store.findById(stored.checkId()).orElseThrow();
        }

        // 스케줄 재생성·계약 수정과 같은 계약 행 잠금으로 직렬화해 헤더와 회차를 한 판으로 읽는다.
        if (contractLock.lock(contractId) == null) throw notFound(contractId);
        ContractFacts contract = store.findContract(contractId).orElseThrow(() -> notFound(contractId));
        Evaluation evaluation = evaluate(contract, request.paymentStage());
        long id = store.insert(evaluation.response(), evaluation.limitPolicyVersionId(), idempotencyKey, hash, userId);
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(AUDIT_ACTION)
                .entityType("ACQUISITION_COST_CHECK")
                .entityId(String.valueOf(id))
                .userId(userId)
                .policyVersionId(evaluation.limitPolicyVersionId())
                .after(Map.of("contractId", contractId, "paymentStage", request.paymentStage().name(),
                        "resultStatus", evaluation.response().resultStatus().name()))
                .build());
        return store.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public AcquisitionCostCheckResponse detail(long id) {
        return store.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<AcquisitionCostCheckResponse> search(AcquisitionCostCheckSearchCondition condition, int page,
                                                             int size) {
        if (page < 1 || (long) (page - 1) * size > Integer.MAX_VALUE) throw invalid("page");
        if (size < 1 || size > 100) throw invalid("size");
        ListSort sort = ListSort.parse(condition.getSort(), AcquisitionCostCheckRepository.SORTABLE.keySet(),
                AcquisitionCostCheckRepository.DEFAULT_SORT);
        var result = store.search(condition, sort, page, size);
        return PageResponse.of(result.items(), page, size, result.total(), sort.value());
    }

    private record Evaluation(AcquisitionCostCheckResponse response, Long limitPolicyVersionId) {
    }

    private Evaluation evaluate(ContractFacts contract, PaymentStage stage) {
        // 제4-32조 제11·12항은 보장성보험에만 적용한다(REG-01). 판매버전 체계와 별개인 법정 범위다.
        if (!"PROTECTION".equals(contract.protectionType())) {
            return new Evaluation(unresolved(contract, stage, AcquisitionCostResultStatus.NOT_APPLICABLE,
                    new AcquisitionCostReason("NOT_PROTECTION_PRODUCT", "보장성보험이 아니라 한도 적용 대상이 아닙니다.")), null);
        }
        FeeRegimeRulesetSelection limits = rulesets.resolve(contract.contractId());
        if (limits.resolution().status() == FeeRegimeResolutionStatus.NOT_APPLICABLE) {
            return new Evaluation(unresolved(contract, stage, AcquisitionCostResultStatus.NOT_APPLICABLE,
                    regimeReason(limits.resolution().reasonCode())), null);
        }
        if (limits.resolution().status() == FeeRegimeResolutionStatus.REVIEW_REQUIRED) {
            return new Evaluation(unresolved(contract, stage, AcquisitionCostResultStatus.REVIEW_REQUIRED,
                    regimeReason(limits.resolution().reasonCode())), null);
        }

        long limitPolicyId = limits.ruleset().policyVersionId();
        List<AcquisitionCostReason> reasons = new ArrayList<>();
        AcquisitionCostPolicyRef paymentPolicy = null;
        AcquisitionCostBasis basis = null;
        ScheduleRef schedule = null;
        List<Line> lines = null;
        // 기준율과 예정 회차는 이 계약·지급단계의 실제 지급 정책에 있다. 한도율은 규제 상한 룰셋에서 읽는다(운영정책서 제46조).
        FeeRegimeRulesetSelection payment = rulesets.resolveForSchedule(contract.contractId(), stage);
        if (payment.isSelected()) {
            long paymentPolicyId = payment.ruleset().policyVersionId();
            paymentPolicy = store.findPolicy(paymentPolicyId);
            basis = basis(contract, store.findParameters(paymentPolicyId,
                    List.of(AcquisitionCostParameter.BASIS, AcquisitionCostParameter.RATE_PCT)), reasons);
            List<ScheduleRef> schedules = store.findActiveSchedules(contract.contractId(), stage,
                    payment.ruleset().feeRegimeCode(), paymentPolicyId);
            if (schedules.size() == 1) {
                schedule = schedules.getFirst();
                lines = store.findLines(schedule.scheduleHeaderId());
            } else {
                reasons.add(schedules.isEmpty()
                        ? new AcquisitionCostReason("SCHEDULE_MISSING", "이 지급 정책으로 만든 활성 예정 스케줄이 없습니다.")
                        : new AcquisitionCostReason("SCHEDULE_AMBIGUOUS", "활성 예정 스케줄이 둘 이상이라 하나로 정할 수 없습니다."));
            }
        } else {
            AcquisitionCostReason cause = regimeReason(payment.resolution().reasonCode());
            reasons.add(new AcquisitionCostReason(cause.code(), "이 지급단계의 지급 정책을 고르지 못했습니다. " + cause.message()));
        }

        var validated = ContractAcquisitionCostValidator.validate(new ContractAcquisitionCostValidator.Input(
                basis == null ? null : basis.amount(),
                limits.limit("UPFRONT_TOTAL").map(FeeRegimeRulesetSelection.RulesetLimit::limitPct).orElse(null),
                limits.limit("MAINTENANCE_TOTAL").map(FeeRegimeRulesetSelection.RulesetLimit::limitPct).orElse(null),
                lines));
        reasons.addAll(validated.reasons());
        return new Evaluation(new AcquisitionCostCheckResponse(null, contract.contractId(), contract.contractNo(),
                contract.insurerName(), contract.productName(), contract.offeringVersion(),
                contract.basicDocumentVersion(), contract.basicDocumentDate(), contract.salesStartDate(),
                contract.contractDate(), contract.feeRegimeCode(), stage, stage.label(), validated.status(),
                validated.status().label(), basis, validated.upfront(), validated.maintenance(),
                validated.indirectSupportAmount(), validated.unclassifiedAmount(), validated.exclusions(),
                validated.lineGroups(), reasons, store.findPolicy(limitPolicyId), paymentPolicy,
                schedule == null ? null : schedule.scheduleHeaderId(),
                schedule == null ? null : schedule.scheduleVersionNo(), CALC_RULE_VERSION, null), limitPolicyId);
    }

    /** 기준액 = 월납환산 초회보험료 × 기준율. 입력이 하나라도 없으면 기준액을 만들지 않는다. */
    private static AcquisitionCostBasis basis(ContractFacts contract, Map<String, Parameter> parameters,
                                              List<AcquisitionCostReason> reasons) {
        Parameter basisParameter = parameters.get(AcquisitionCostParameter.BASIS);
        Parameter rateParameter = parameters.get(AcquisitionCostParameter.RATE_PCT);
        String basisCode = basisParameter == null ? null : basisParameter.value();
        BigDecimal rate = rateParameter == null ? null : decimal(rateParameter.value());
        String description = rateParameter == null ? null : rateParameter.description();
        BigDecimal premium = contract.monthlyEquivalentFirstPremium();
        BigDecimal amount = null;
        if (rate == null || rate.signum() <= 0 || !AcquisitionCostParameter.MONTHLY_EQUIVALENT_FIRST_PREMIUM.equals(basisCode)) {
            reasons.add(new AcquisitionCostReason("ACQUISITION_COST_MISSING",
                    "지급 정책에 계약체결비용 기준(" + AcquisitionCostParameter.BASIS + ", "
                            + AcquisitionCostParameter.RATE_PCT + ")이 없거나 지원하지 않는 값입니다."));
        } else if (premium == null || premium.signum() <= 0) {
            reasons.add(new AcquisitionCostReason("PREMIUM_MISSING", "월납환산 초회보험료가 없어 기준액을 계산하지 못했습니다."));
        } else {
            amount = premium.multiply(rate).divide(HUNDRED, 0, RoundingMode.HALF_UP);
        }
        return new AcquisitionCostBasis(amount, basisCode, premium, rate, description);
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 룰셋을 고르지 못한 결과. 금액을 0으로 채우지 않는다. */
    private static AcquisitionCostCheckResponse unresolved(ContractFacts contract, PaymentStage stage,
                                                           AcquisitionCostResultStatus status, AcquisitionCostReason reason) {
        return new AcquisitionCostCheckResponse(null, contract.contractId(), contract.contractNo(),
                contract.insurerName(), contract.productName(), contract.offeringVersion(),
                contract.basicDocumentVersion(), contract.basicDocumentDate(), contract.salesStartDate(),
                contract.contractDate(), contract.feeRegimeCode(), stage, stage.label(), status, status.label(), null,
                null, null, null, null, List.of(), List.of(), List.of(reason), null, null, null, null,
                CALC_RULE_VERSION, null);
    }

    private static AcquisitionCostReason regimeReason(FeeRegimeReasonCode code) {
        return new AcquisitionCostReason(code.name(), switch (code) {
            case UNKNOWN_REGIME -> "판매버전의 수수료 체계 코드를 확인할 수 없습니다.";
            case CONTRACT_OUTSIDE_SALES_PERIOD -> "계약일이 판매기간 밖입니다.";
            case OFFERING_NOT_UNIQUE -> "계약일을 포함하는 판매버전이 하나가 아닙니다.";
            case NOT_APPLICABLE -> "판매개시일과 기초서류가 분급 적용례를 충족하지 않아 적용 대상이 아닙니다.";
            case CURRENT_BUT_ELIGIBLE -> "적용례를 충족하는데 현행 체계로 등록돼 있습니다.";
            case REGIME_NOT_ELIGIBLE -> "4년·7년 체계로 등록됐지만 적용례를 충족하지 않습니다.";
            case FOUR_YEAR_AFTER_SPECIAL_PERIOD -> "4년 특례 종료 뒤의 판매개시 또는 계약이라 적용 체계를 확정할 수 없습니다.";
            case SEVEN_YEAR_IN_SPECIAL_PERIOD -> "4년 특례 기간에 7년 체계로 등록돼 있습니다.";
            case RULESET_MISSING -> "적용할 분급 룰셋이 없습니다.";
            case RULESET_AMBIGUOUS -> "적용할 분급 룰셋이 둘 이상입니다.";
            case RULESET_FLAGGED -> "경계사례로 표시된 룰셋이라 자동으로 확정하지 않습니다.";
            case RULESET_LIMIT_MISSING -> "룰셋에 필수 한도율이 없습니다.";
            case COMMISSION_RULE_INVALID -> "지급 규칙이 룰셋 조건과 맞지 않습니다.";
            case RULESET_SELECTED -> "룰셋을 선택했습니다.";
        });
    }

    private static String hash(long contractId, PaymentStage stage) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((contractId + ":" + stage.name()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }

    private static FgcBusinessException notFound(long id) {
        return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", id));
    }
}
