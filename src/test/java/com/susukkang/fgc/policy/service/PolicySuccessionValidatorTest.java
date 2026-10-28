package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FUN-017·제49조의 전체 대체본 범위 검증. 날짜/회차를 분할해도 기존 유효 범위에 구멍이 없어야 한다. */
class PolicySuccessionValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final PolicySuccessionValidator validator = new PolicySuccessionValidator();

    @Test
    void changingRatesRetainsTheOriginalSelectionScope() throws Exception {
        ObjectNode previous = document(), replacement = previous.deepCopy();
        ((ObjectNode) replacement.path("commissionRules").get(0)).put("ratePct", "25.000001");
        assertValid(previous, replacement);
    }

    @Test
    void rejectsLosingPastDatesOpenEndedCoverageOrHeaderWildcard() throws Exception {
        ObjectNode previous = document();
        assertInvalid(previous, previous.deepCopy().put("effectiveFrom", "2027-01-01"), "effectiveFrom");
        assertInvalid(previous, previous.deepCopy().put("effectiveTo", "2099-12-31"), "effectiveTo");
        assertInvalid(previous, previous.deepCopy().put("basicDocumentVersion", "NARROW"), "basicDocumentVersion");
        assertInvalid(previous, previous.deepCopy().put("policyType", "SCHEDULE_ELIGIBILITY"), "policyType");
    }

    @Test
    void rejectsDroppingItemsOrNarrowingCommissionWildcards() throws Exception {
        ObjectNode previous = document(), replacement = previous.deepCopy();
        ((ObjectNode) replacement.path("commissionRules").get(0)).put("insurerId", 7);
        assertInvalid(previous, replacement, "commissionRules");
        previous.withArray("commissionRules").add(previous.path("commissionRules").get(0).deepCopy());
        ((ObjectNode) previous.path("commissionRules").get(1)).put("commissionItemId", 2);
        assertInvalid(previous, document(), "commissionRules");
    }

    @Test
    void permitsSplitInstallmentsOnlyWhenTheUnionHasNoGap() throws Exception {
        ObjectNode previous = document(), replacement = previous.deepCopy();
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
        ObjectNode replacement = previous.deepCopy();
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
        replacement.withArray("feeRegimeRules").remove(3);
        assertInvalid(previous, replacement, "feeRegimeRules");
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
