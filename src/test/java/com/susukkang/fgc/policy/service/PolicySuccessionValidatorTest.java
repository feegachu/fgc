package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyFeeLimit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FUN-017·제49조의 전체 대체본 범위 검증. 날짜/회차를 분할해도 기존 유효 범위에 구멍이 없어야 한다. */
class PolicySuccessionValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final PolicySuccessionValidator validator = new PolicySuccessionValidator();

    @Test
    void permitsChangedPricesAndPriorityFromTheNewContractDate() throws Exception {
        ObjectNode previous = document(), replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        assertValid(previous, replacement);
        ((ObjectNode) replacement.path("commissionRules").get(0)).put("ratePct", "25.000001");
        assertValid(previous, replacement);
        replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        ((ObjectNode) replacement.path("commissionRules").get(0)).put("priorityNo", 2);
        assertValid(previous, replacement);
        replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        replacement.withArray("commissionRules").add(replacement.path("commissionRules").get(0).deepCopy());
        ((ObjectNode) replacement.path("commissionRules").get(1)).put("commissionItemId", 99);
        assertValid(previous, replacement);
    }

    @Test
    void permitsFuturePricesInBothNewAndExistingScopes() throws Exception {
        ObjectNode previous = document();
        ((ObjectNode) previous.path("commissionRules").get(0)).put("insurerId", 1);
        ObjectNode replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        replacement.withArray("commissionRules").add(previous.path("commissionRules").get(0).deepCopy());
        ((ObjectNode) replacement.path("commissionRules").get(1)).put("insurerId", 2).put("ratePct", "25.000001");
        assertValid(previous, replacement);
        ((ObjectNode) replacement.path("commissionRules").get(1)).putNull("insurerId");
        assertValid(previous, replacement);
    }

    @Test
    void requiresLaterCutoverWithoutGapsOrNarrowerCoverage() throws Exception {
        ObjectNode previous = document();
        assertInvalid(previous, previous.deepCopy().put("effectiveFrom", "2025-12-31"), "effectiveFrom");
        assertInvalid(previous, previous.deepCopy(), "effectiveFrom");
        assertValid(previous, previous.deepCopy().put("effectiveFrom", "2027-01-01"));
        assertInvalid(previous, previous.deepCopy().put("effectiveFrom", "2027-01-01").put("effectiveTo", "2099-12-31"), "effectiveTo");
        assertInvalid(previous, previous.deepCopy().put("basicDocumentVersion", "NARROW"), "basicDocumentVersion");
        assertInvalid(previous, previous.deepCopy().put("policyType", "SCHEDULE_ELIGIBILITY"), "policyType");
        previous.put("effectiveTo", "2026-12-31");
        assertValid(previous, previous.deepCopy().put("effectiveFrom", "2027-01-01").putNull("effectiveTo"));
        assertInvalid(previous, previous.deepCopy().put("effectiveFrom", "2027-01-02").putNull("effectiveTo"), "effectiveFrom");
    }

    @Test
    void rejectsDroppingItemsOrNarrowingCommissionWildcards() throws Exception {
        ObjectNode previous = document(), replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        ((ObjectNode) replacement.path("commissionRules").get(0)).put("insurerId", 7);
        assertInvalid(previous, replacement, "commissionRules");
        previous.withArray("commissionRules").add(previous.path("commissionRules").get(0).deepCopy());
        ((ObjectNode) previous.path("commissionRules").get(1)).put("commissionItemId", 2);
        assertInvalid(previous, document().put("effectiveFrom", "2027-01-01"), "commissionRules");
    }

    @Test
    void permitsSplitInstallmentsOnlyWhenTheUnionHasNoGap() throws Exception {
        ObjectNode previous = document(), replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        ObjectNode first = (ObjectNode) replacement.path("commissionRules").get(0);
        first.put("installmentTo", 6);
        replacement.withArray("commissionRules").add(first.deepCopy().put("installmentFrom", 7).put("installmentTo", 12));
        assertValid(previous, replacement);
        ((ObjectNode) replacement.path("commissionRules").get(1)).put("installmentFrom", 8);
        assertInvalid(previous, replacement, "commissionRules");
    }

    @Test
    void verifiesTheEntireSalesAndDocumentDateGrid() throws Exception {
        ObjectNode previous = document().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        ObjectNode replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        ObjectNode oldFee = JSON.createObjectNode().put("feeRegimeCode", "FOUR_YEAR_2027")
                .put("salesStartDateFrom", "2027-01-01").put("salesStartDateTo", "2028-12-31")
                .put("basicDocumentDateFrom", "2027-01-01").put("basicDocumentDateTo", "2028-12-31")
                .put("maxInstallments", 48);
        oldFee.putArray("sourceRefs");
        previous.withArray("feeRegimeRules").add(oldFee);
        for (int salesYear : new int[]{2027, 2028}) {
            for (int documentYear : new int[]{2027, 2028}) {
                replacement.withArray("feeRegimeRules").add(oldFee.deepCopy()
                        .put("salesStartDateFrom", salesYear + "-01-01").put("salesStartDateTo", salesYear + "-12-31")
                        .put("basicDocumentDateFrom", documentYear + "-01-01").put("basicDocumentDateTo", documentYear + "-12-31"));
            }
        }
        assertValid(previous, replacement);
        ((ObjectNode) replacement.path("feeRegimeRules").get(3)).put("maxInstallments", 36);
        assertValid(previous, replacement);
        ((ObjectNode) replacement.path("feeRegimeRules").get(3)).put("maxInstallments", 48);
        replacement.withArray("feeRegimeRules").remove(3);
        assertInvalid(previous, replacement, "feeRegimeRules");
    }

    @Test
    void permitsParameterChangesInTheNewApplicationWindow() throws Exception {
        ObjectNode previous = document().put("policyType", "ARBITRAGE");
        previous.putArray("commissionRules");
        previous.withArray("parameters").addObject().put("key", "ARBITRAGE_REFUND_ADDITION_MONTHS").put("value", 12);
        ObjectNode replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        ((ObjectNode) replacement.path("parameters").get(0)).put("value", 24);
        assertValid(previous, replacement);
    }

    @Test
    void permitsReplacingSpecificPricesByWildcardForFutureContracts() throws Exception {
        ObjectNode previous = document();
        previous.withArray("commissionRules").add(((ObjectNode) previous.path("commissionRules").get(0)).deepCopy()
                .put("insurerId", 7).put("ratePct", "20.000000"));
        ObjectNode replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        replacement.withArray("commissionRules").remove(1);
        assertValid(previous, replacement);
    }

    @Test
    void permitsChangingFutureRegimeConditionsWhilePreservingCoverage() throws Exception {
        ObjectNode previous = document().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        ObjectNode wildcard = JSON.createObjectNode().put("feeRegimeCode", "FOUR_YEAR_2027")
                .put("salesStartDateFrom", "2027-01-01").put("maxInstallments", 48);
        wildcard.putArray("sourceRefs");
        previous.withArray("feeRegimeRules").add(wildcard);
        previous.withArray("feeRegimeRules").add(wildcard.deepCopy().put("basicDocumentVersion", "DOC-1").put("maxInstallments", 36));
        ObjectNode replacement = previous.deepCopy().put("effectiveFrom", "2027-01-01");
        replacement.withArray("feeRegimeRules").remove(1);
        assertValid(previous, replacement);
    }

    @Test
    void preservesHiddenRegimeLimitsAcrossNewIdsAndRejectsMissingOrChangedLimits() throws Exception {
        ObjectNode previous = document().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        previous.withArray("feeRegimeRules").addObject().put("feeRegimeRulesetId", 1)
                .put("feeRegimeCode", "FOUR_YEAR_2027").put("salesStartDateFrom", "2027-01-01").put("maxInstallments", 48);
        ObjectNode replacement = previous.deepCopy();
        ((ObjectNode) replacement.path("feeRegimeRules").get(0)).put("feeRegimeRulesetId", 2);
        var originalLimits = Map.of(1L, Set.of(new PolicyFeeLimit("UPFRONT_TOTAL", new BigDecimal("100.0"), true)));
        var copiedLimits = Map.of(2L, Set.of(new PolicyFeeLimit("UPFRONT_TOTAL", new BigDecimal("100.0000"), true)));
        assertThatCode(() -> validator.validateFeeLimits(draft(previous), draft(replacement), originalLimits, copiedLimits))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateFeeLimits(draft(previous), draft(replacement), originalLimits, Map.of()))
                .isInstanceOf(FgcBusinessException.class);
        var changedLimit = Map.of(2L, Set.of(new PolicyFeeLimit("UPFRONT_TOTAL", new BigDecimal("90"), true)));
        assertThatThrownBy(() -> validator.validateFeeLimits(draft(previous), draft(replacement), originalLimits, changedLimit))
                .isInstanceOf(FgcBusinessException.class);
        var changedIndirect = Map.of(2L, Set.of(new PolicyFeeLimit("UPFRONT_TOTAL", new BigDecimal("100"), false)));
        assertThatThrownBy(() -> validator.validateFeeLimits(draft(previous), draft(replacement), originalLimits, changedIndirect))
                .isInstanceOf(FgcBusinessException.class);
    }

    private void assertValid(ObjectNode previous, ObjectNode replacement) {
        assertThatCode(() -> validator.validate(draft(previous), draft(replacement))).doesNotThrowAnyException();
    }

    private void assertInvalid(ObjectNode previous, ObjectNode replacement, String field) {
        assertThatThrownBy(() -> validator.validate(draft(previous), draft(replacement)))
                .isInstanceOfSatisfying(FgcBusinessException.class, error -> assertThat(error.getField()).isEqualTo(field));
    }

    private static PolicyDraft draft(ObjectNode value) { return JSON.convertValue(value, PolicyDraft.class); }

    private static ObjectNode document() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"policyCode":"SUCCESSION","policyName":"전체 대체본","policyType":"CURRENT_COMMISSION",
                 "sourceClass":"GA_POLICY","effectiveFrom":"2026-01-01","effectiveTo":null,
                 "regulationRefs":[],"sourceRefs":[],"sourceDetails":[],"feeRegimeRules":[],"parameters":[],"clawbackRules":[],
                 "commissionRules":[{"paymentStage":"GA_TO_FC","commissionItemId":1,"feeComponentType":"CURRENT",
                 "installmentFrom":1,"installmentTo":12,"calculationType":"RATE","basisCode":"MONTHLY_EQUIVALENT_FIRST_PREMIUM",
                 "ratePct":"10.000000","priorityNo":1}]}
                """);
    }
}
