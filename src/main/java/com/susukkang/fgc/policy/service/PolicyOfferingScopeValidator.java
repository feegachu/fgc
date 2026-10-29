package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.base.entity.ProductOffering;
import com.susukkang.fgc.base.repository.ProductOfferingRepository;
import com.susukkang.fgc.base.repository.ProductRepository;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** FUN-014: 초안 작성은 허용하되 제출·승인·활성화 시 판매버전과 분급 조건을 대조한다. */
@Component
@RequiredArgsConstructor
public class PolicyOfferingScopeValidator {
    private static final String PRODUCT_OFFERING_ID = ".productOfferingId";
    private final ProductOfferingRepository offeringRepository;
    private final ProductRepository productRepository;

    public void validateForSubmission(PolicyDraft draft) {
        if (draft.policyType() != PolicyType.FOUR_YEAR_COMMISSION
                && draft.policyType() != PolicyType.SEVEN_YEAR_COMMISSION) return;
        Map<Long, ProductOffering> offerings = new HashMap<>();
        for (int i = 0; i < draft.commissionRules().size(); i++) {
            var rule = draft.commissionRules().get(i);
            if (rule.productOfferingId() == null) continue;
            String field = "commissionRules[" + i + "]";
            ProductOffering offering = offerings.computeIfAbsent(rule.productOfferingId(), id ->
                    offeringRepository.findById(id).orElseThrow(() ->
                            invalid(field + PRODUCT_OFFERING_ID, "존재하지 않는 상품 판매버전입니다.")));
            validateInsurer(rule, offering, field);
            if (!Objects.equals(draft.feeRegimeCode(), offering.getFeeRegimeCode())
                    || !matchesVersion(draft.basicDocumentVersion(), offering.getBasicDocumentVersion())
                    || !overlaps(draft.effectiveFrom(), draft.effectiveTo(),
                                 offering.getSalesStartDate(), offering.getSalesEndDate())) {
                throw invalid(field + PRODUCT_OFFERING_ID,
                        "상품 판매버전의 수수료체계·기초서류·판매기간과 정책 적용 조건이 일치해야 합니다.");
            }
            List<PolicyDraft.FeeRegimeRuleDraft> matches = draft.feeRegimeRules().stream()
                    .filter(regime -> matchesVersion(regime.basicDocumentVersion(), offering.getBasicDocumentVersion())
                            && contains(regime.salesStartDateFrom(), regime.salesStartDateTo(), offering.getSalesStartDate())
                            && contains(regime.basicDocumentDateFrom(), regime.basicDocumentDateTo(), offering.getBasicDocumentDate()))
                    .toList();
            // ProductFeeRegimeResolver와 같은 정책 안의 기초서류 특정 버전 우선 규칙.
            if (matches.stream().anyMatch(regime -> regime.basicDocumentVersion() != null)) {
                matches = matches.stream().filter(regime -> regime.basicDocumentVersion() != null).toList();
            }
            if (matches.size() != 1) {
                throw invalid(field + PRODUCT_OFFERING_ID,
                        "이 판매버전의 판매개시일·기초서류에 적용되는 분급 룰셋이 정확히 하나여야 합니다.");
            }
            PolicyDraftValidator.validateCommissionRegimeBound(rule, matches.getFirst(), field);
        }
    }

    private void validateInsurer(PolicyDraft.CommissionRuleDraft rule, ProductOffering offering, String field) {
        if (rule.insurerId() != null && !Objects.equals(rule.insurerId(),
                productRepository.findById(offering.getProductId()).orElseThrow().getInsurerId())) {
            throw invalid(field + ".insurerId", "선택한 보험회사에 속한 상품 판매버전이어야 합니다.");
        }
    }

    private static boolean matchesVersion(String condition, String actual) {
        return condition == null || Objects.equals(condition, actual);
    }

    private static boolean contains(LocalDate from, LocalDate to, LocalDate date) {
        return (from == null && to == null) || (date != null
                && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to)));
    }

    private static boolean overlaps(LocalDate from, LocalDate to, LocalDate otherFrom, LocalDate otherTo) {
        return (to == null || !to.isBefore(otherFrom)) && (otherTo == null || !otherTo.isBefore(from));
    }

    private static FgcBusinessException invalid(String field, String message) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), message);
    }
}
