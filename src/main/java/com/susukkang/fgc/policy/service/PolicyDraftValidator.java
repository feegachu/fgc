package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.FeeComponentType;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyDraft.CommissionRuleDraft;
import com.susukkang.fgc.policy.dto.PolicyDraft.FeeRegimeRuleDraft;
import com.susukkang.fgc.policy.dto.PolicyDraft.ParameterDraft;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 초안의 저장 가능 형식만 검증한다. 최소 규칙 수·근거 존재 여부는 제출 단계에서 검증한다. */
@Component
public class PolicyDraftValidator {
    private static final Set<PolicyType> COMMISSION_TYPES = Set.of(
            PolicyType.CURRENT_COMMISSION, PolicyType.FOUR_YEAR_COMMISSION, PolicyType.SEVEN_YEAR_COMMISSION);
    private static final String FOUR_YEAR = "FOUR_YEAR_2027";
    private static final String SEVEN_YEAR = "SEVEN_YEAR_2029";
    private static final String FIELD_FEE_REGIME = "feeRegimeCode";
    private static final String FEE_RULE_PREFIX = "feeRegimeRules[";
    private static final String COMMISSION_RULE_PREFIX = "commissionRules[";
    private static final Set<String> HEADER_REGIMES = Set.of("CURRENT", FOUR_YEAR, SEVEN_YEAR);
    private static final Map<String, String> SCHEDULE_VALUES = Map.of(
            "STATUS_MONTH_RULE", "PAY_IF_ACTIVE_ON_DUE_DATE",
            "PRORATION_RULE", "NO_PRORATION",
            "REVIVAL_BACKFILL_RULE", "NO_RETROACTIVE");

    private final Validator validator;

    public PolicyDraftValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(PolicyDraft draft) {
        if (draft == null) {
            throw invalid("policyDraft", "정책 초안이 필요합니다.");
        }
        validator.validate(draft).stream()
                .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .ifPresent(v -> { throw invalid(v.getPropertyPath().toString(), v.getMessage()); });
        checkPeriod(draft.effectiveFrom(), draft.effectiveTo(), "effectiveTo");
        String requiredRegime = validateHeaderRegime(draft);
        validateSourceDetails(draft);
        // D-02 환수 기준은 아직 미결이다. 입력을 조용히 버리거나 임의 규칙을 만들지 않는다.
        if (!draft.clawbackRules().isEmpty()) {
            throw invalid("clawbackRules", "D-02 환수 기준 확정 전에는 환수 규칙을 등록할 수 없습니다.");
        }
        validateCommissionRules(draft);
        validateFeeRules(draft, requiredRegime);
        validateParameters(draft);
    }

    private static String validateHeaderRegime(PolicyDraft draft) {
        if (draft.feeRegimeCode() != null && !HEADER_REGIMES.contains(draft.feeRegimeCode())) {
            throw invalid(FIELD_FEE_REGIME, "지원하지 않는 수수료 체계입니다.");
        }
        String requiredRegime = switch (draft.policyType()) {
            case FOUR_YEAR_COMMISSION -> FOUR_YEAR;
            case SEVEN_YEAR_COMMISSION -> SEVEN_YEAR;
            default -> null;
        };
        if (requiredRegime != null && !requiredRegime.equals(draft.feeRegimeCode())) {
            throw invalid(FIELD_FEE_REGIME, "분급 정책 유형에 맞는 수수료 체계가 필요합니다.");
        }
        // 현행 계산 정책 선택기는 NULL 또는 CURRENT인 헤더만 사용한다.
        if (draft.policyType() == PolicyType.CURRENT_COMMISSION
                && draft.feeRegimeCode() != null && !"CURRENT".equals(draft.feeRegimeCode())) {
            throw invalid(FIELD_FEE_REGIME, "현행 수수료 정책의 체계는 CURRENT 또는 미지정이어야 합니다.");
        }
        return requiredRegime;
    }

    private static void validateSourceDetails(PolicyDraft draft) {
        Set<String> detailRefs = new HashSet<>();
        for (int i = 0; i < draft.sourceDetails().size(); i++) {
            if (!detailRefs.add(draft.sourceDetails().get(i).ref())) {
                throw invalid("sourceDetails[" + i + "].ref", "동일한 근거 상세를 중복 지정할 수 없습니다.");
            }
            String url = draft.sourceDetails().get(i).url();
            if (url != null && !url.isBlank() && !isWebUrl(url)) {
                throw invalid("sourceDetails[" + i + "].url", "근거 링크는 http 또는 https 주소만 등록할 수 있습니다.");
            }
        }
    }

    /** info 패널이 새 창으로 여는 링크. 스크립트·파일 등 다른 scheme과 공백·제어문자, 사용자 정보가 든 주소는 받지 않는다. */
    static boolean isWebUrl(String url) {
        if (!url.equals(url.strip()) || url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static void validateCommissionRules(PolicyDraft draft) {
        if (!draft.commissionRules().isEmpty() && !COMMISSION_TYPES.contains(draft.policyType())) {
            throw invalid("commissionRules", "수수료 규칙은 현행·4년·7년 수수료 정책에만 등록할 수 있습니다.");
        }
        Set<Long> commissionIds = new HashSet<>();
        for (int i = 0; i < draft.commissionRules().size(); i++) {
            CommissionRuleDraft rule = draft.commissionRules().get(i);
            if (rule.commissionRuleId() != null && !commissionIds.add(rule.commissionRuleId())) {
                throw invalid(COMMISSION_RULE_PREFIX + i + "].commissionRuleId", "동일한 자식 규칙 ID를 중복 지정할 수 없습니다.");
            }
            validateCommission(rule, COMMISSION_RULE_PREFIX + i + "]");
        }
    }

    private static void validateFeeRules(PolicyDraft draft, String requiredRegime) {
        if (!draft.feeRegimeRules().isEmpty() && requiredRegime == null) {
            throw invalid("feeRegimeRules", "분급 룰셋은 4년·7년 수수료 정책에만 등록할 수 있습니다.");
        }
        Set<Long> feeIds = new HashSet<>();
        for (int i = 0; i < draft.feeRegimeRules().size(); i++) {
            FeeRegimeRuleDraft rule = draft.feeRegimeRules().get(i);
            if (rule.feeRegimeRulesetId() != null && !feeIds.add(rule.feeRegimeRulesetId())) {
                throw invalid(FEE_RULE_PREFIX + i + "].feeRegimeRulesetId", "동일한 자식 규칙 ID를 중복 지정할 수 없습니다.");
            }
            validateFeeRule(rule, requiredRegime, FEE_RULE_PREFIX + i + "]");
            if (draft.basicDocumentVersion() != null && rule.basicDocumentVersion() != null
                    && !draft.basicDocumentVersion().equals(rule.basicDocumentVersion())) {
                throw invalid(FEE_RULE_PREFIX + i + "].basicDocumentVersion",
                        "정책과 분급 룰셋의 기초서류 버전이 일치해야 합니다.");
            }
        }
        validateFeeOverlaps(draft.feeRegimeRules());
    }

    private static void validateParameters(PolicyDraft draft) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < draft.parameters().size(); i++) {
            ParameterDraft parameter = draft.parameters().get(i);
            String field = "parameters[" + i + "]";
            if (!keys.add(parameter.key())) {
                throw invalid(field + ".key", "같은 정책 파라미터 키를 중복 등록할 수 없습니다.");
            }
            if (!validParameter(draft.policyType(), parameter)) {
                throw invalid(field + ".value", "해당 정책 유형에서 지원하지 않는 파라미터 키 또는 값입니다.");
            }
        }
    }

    /** 제출 단계에서는 같은 회차의 최종 규칙을 고를 수 없는 중복도 차단한다. */
    public void validateForSubmission(PolicyDraft draft) {
        validate(draft);
        validateCommissionRegimeBounds(draft);
        List<CommissionRuleDraft> rules = draft.commissionRules();
        for (int i = 0; i < rules.size(); i++) {
            CommissionRuleDraft left = rules.get(i);
            for (int j = i + 1; j < rules.size(); j++) {
                CommissionRuleDraft right = rules.get(j);
                // CommissionPolicyServiceImpl의 RuleKey에는 feeComponentType이 없다.
                // 구성 유형이 달라도 항목·직급·회차와 적용 범위·우선순위가 같으면 선택 결과가 모호하다.
                if (left.paymentStage() == right.paymentStage()
                        && Objects.equals(left.commissionItemId(), right.commissionItemId())
                        && left.agentRankCode() == right.agentRankCode()
                        && Objects.equals(left.insurerId(), right.insurerId())
                        && Objects.equals(left.productOfferingId(), right.productOfferingId())
                        && Objects.equals(left.organizationId(), right.organizationId())
                        && Objects.equals(left.priorityNo(), right.priorityNo())
                        && left.installmentFrom() <= right.installmentTo()
                        && right.installmentFrom() <= left.installmentTo()) {
                    throw new FgcBusinessException(FgcErrorCode.POL_002, COMMISSION_RULE_PREFIX + j + "]",
                            Map.of("conflictingIndex", i), "같은 적용 범위·우선순위의 수수료 규칙 회차가 겹칩니다.");
                }
            }
        }
    }

    private static void validateCommissionRegimeBounds(PolicyDraft draft) {
        if (draft.policyType() != PolicyType.FOUR_YEAR_COMMISSION
                && draft.policyType() != PolicyType.SEVEN_YEAR_COMMISSION) return;
        for (int i = 0; i < draft.commissionRules().size(); i++) {
            CommissionRuleDraft rule = draft.commissionRules().get(i);
            // 판매버전을 지정한 규칙은 DB의 판매일·기초서류로 좁힌 룰셋과 비교한다.
            if (rule.productOfferingId() != null) continue;
            for (FeeRegimeRuleDraft regime : draft.feeRegimeRules()) {
                validateCommissionRegimeBound(rule, regime, COMMISSION_RULE_PREFIX + i + "]");
            }
        }
    }

    // 수수료 규칙은 판매일별로 나뉘지 않으므로 해당 정책의 모든 분급 룰셋에서 유효해야 한다.
    static void validateCommissionRegimeBound(CommissionRuleDraft rule, FeeRegimeRuleDraft regime, String field) {
        if (rule.installmentTo() > regime.maxInstallments()) {
            throw invalid(field + ".installmentTo", "수수료 규칙은 분급 룰셋의 최대 회차를 넘을 수 없습니다.");
        }
        if (rule.feeComponentType() != FeeComponentType.LONG_TERM_MAINTENANCE) return;
        if (FOUR_YEAR.equals(regime.feeRegimeCode())) {
            throw invalid(field + ".feeComponentType", "4년 분급에는 장기유지 수수료를 등록할 수 없습니다.");
        }
        if (regime.longTermMaintenanceFrom() == null
                || rule.installmentFrom() < regime.longTermMaintenanceFrom()
                || rule.installmentTo() > regime.longTermMaintenanceTo()) {
            throw invalid(field + ".installmentFrom", "장기유지 수수료 규칙은 룰셋의 장기유지 구간 안에 있어야 합니다.");
        }
    }

    private static void validateCommission(CommissionRuleDraft rule, String field) {
        if (rule.installmentTo() < rule.installmentFrom()) {
            throw invalid(field + ".installmentTo", "종료 회차는 시작 회차 이상이어야 합니다.");
        }
        if (!"MONTHLY_EQUIVALENT_FIRST_PREMIUM".equals(rule.basisCode())) {
            throw invalid(field + ".basisCode", "지원하지 않는 수수료 계산 기준입니다.");
        }
        if (rule.calculationType() == CalculationType.RATE
                && (rule.ratePct() == null || rule.fixedAmount() != null)) {
            throw invalid(field + ".ratePct", "정률 규칙에는 요율만 지정해야 합니다.");
        }
        if (rule.calculationType() == CalculationType.FIXED
                && (rule.fixedAmount() == null || rule.ratePct() != null)) {
            throw invalid(field + ".fixedAmount", "정액 규칙에는 고정금액만 지정해야 합니다.");
        }
    }

    private static void validateFeeRule(FeeRegimeRuleDraft rule, String requiredRegime, String field) {
        if (!Objects.equals(requiredRegime, rule.feeRegimeCode())) {
            throw invalid(field + ".feeRegimeCode", "정책 유형과 분급 룰셋의 수수료 체계가 일치해야 합니다.");
        }
        checkPeriod(rule.salesStartDateFrom(), rule.salesStartDateTo(), field + ".salesStartDateTo");
        checkPeriod(rule.basicDocumentDateFrom(), rule.basicDocumentDateTo(), field + ".basicDocumentDateTo");
        Integer from = rule.longTermMaintenanceFrom();
        Integer to = rule.longTermMaintenanceTo();
        if ((from == null) != (to == null)
                || (from != null && (to < from || to > rule.maxInstallments()))) {
            throw invalid(field + ".longTermMaintenanceTo", "장기유지 구간의 양 끝과 최대 회차를 확인해야 합니다.");
        }
        if (FOUR_YEAR.equals(rule.feeRegimeCode())
                && (rule.maxInstallments() > 48 || from != null)) {
            throw invalid(field + ".maxInstallments", "4년 분급은 최대 48회차이며 장기유지 구간을 둘 수 없습니다.");
        }
    }

    /** V59 ex_fee_regime_ruleset_scope의 두 날짜 구간·기초서류 버전 비교와 동일하다. */
    private static void validateFeeOverlaps(List<FeeRegimeRuleDraft> rules) {
        for (int i = 0; i < rules.size(); i++) {
            FeeRegimeRuleDraft left = rules.get(i);
            for (int j = i + 1; j < rules.size(); j++) {
                FeeRegimeRuleDraft right = rules.get(j);
                if (left.feeRegimeCode().equals(right.feeRegimeCode())
                        && Objects.toString(left.basicDocumentVersion(), "")
                            .equals(Objects.toString(right.basicDocumentVersion(), ""))
                        && overlaps(left.salesStartDateFrom(), left.salesStartDateTo(),
                                    right.salesStartDateFrom(), right.salesStartDateTo())
                        && overlaps(left.basicDocumentDateFrom(), left.basicDocumentDateTo(),
                                    right.basicDocumentDateFrom(), right.basicDocumentDateTo())) {
                    throw new FgcBusinessException(FgcErrorCode.POL_002, FEE_RULE_PREFIX + j + "]",
                            Map.of("conflictingIndex", i), "같은 분급 룰셋의 적용 구간이 겹칩니다.");
                }
            }
        }
    }

    private static boolean validParameter(PolicyType type, ParameterDraft parameter) {
        JsonNode value = parameter.value();
        return switch (type) {
            case SCHEDULE_ELIGIBILITY -> value.isTextual()
                    && Objects.equals(SCHEDULE_VALUES.get(parameter.key()), value.textValue());
            case ARBITRAGE -> switch (parameter.key()) {
                case "ARBITRAGE_EFFECTIVE_CONTRACT_DATE" -> validDate(value);
                case "ARBITRAGE_REFUND_ADDITION_MONTHS" -> value.isIntegralNumber() && value.canConvertToInt()
                        && value.intValue() >= 1 && value.intValue() <= 1200;
                case "ARBITRAGE_REFUND_ADDITION_BOUNDARY_INCLUSIVE" -> value.isBoolean();
                default -> false;
            };
            // 현재 ZeroTolerancePolicy는 0원·0일만 구현한다. 다른 값의 저장으로 지원 범위를 가장하지 않는다.
            case RECONCILIATION_TOLERANCE -> Set.of("TOLERANCE_AMOUNT_KRW", "TOLERANCE_DAYS").contains(parameter.key())
                    && value.isNumber() && value.decimalValue().signum() == 0;
            default -> false;
        };
    }

    private static boolean validDate(JsonNode value) {
        if (!value.isTextual()) return false;
        try {
            LocalDate.parse(value.textValue());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean overlaps(LocalDate from1, LocalDate to1, LocalDate from2, LocalDate to2) {
        return (to1 == null || from2 == null || !to1.isBefore(from2))
                && (to2 == null || from1 == null || !to2.isBefore(from1));
    }

    private static void checkPeriod(LocalDate from, LocalDate to, String field) {
        if (from != null && to != null && to.isBefore(from)) {
            throw invalid(field, "종료일은 시작일 이상이어야 합니다.");
        }
    }

    private static FgcBusinessException invalid(String field, String detail) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), detail);
    }
}
