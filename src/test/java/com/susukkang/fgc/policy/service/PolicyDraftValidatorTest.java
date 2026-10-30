package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.PolicyActionRequest;
import com.susukkang.fgc.policy.dto.PolicyCloneRequest;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyDraftValidatorTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final PolicyDraftValidator validator = new PolicyDraftValidator(FACTORY.getValidator());

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void savesIncompleteDraftWithEmptyRulesAndEvidence() {
        assertThatCode(() -> validator.validate(draft(baseDraft()))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingRequiredListAndNullChildBeforeCrossFieldChecks() {
        ObjectNode missingList = baseDraft();
        missingList.remove("commissionRules");
        assertInvalid(missingList, "commissionRules");

        ObjectNode nullChild = baseDraft();
        array(nullChild, "commissionRules").addNull();
        assertInvalid(nullChild, "commissionRules[0]");
    }

    @Test
    void rejectsInvertedPolicyDatesAndBlankNames() {
        ObjectNode reversed = baseDraft().put("effectiveTo", "2025-12-31");
        assertInvalid(reversed, "effectiveTo");
        assertInvalid(baseDraft().put("policyName", "  "), "policyName");
    }

    @Test
    void rejectsServerOwnedAndUnknownFieldsEvenWhenGlobalUnknownPropertyCheckIsDisabled() {
        for (String field : new String[]{"status", "versionNo", "createdBy", "approvedBy", "approvedAt", "typo"}) {
            ObjectNode json = baseDraft().put(field, "injected");
            assertThatThrownBy(() -> draft(json)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("허용되지 않은 정책 초안 필드");
        }
        ObjectNode nested = baseDraft();
        array(nested, "commissionRules").add(commissionRule().put("ruleExpression", "select * from secrets"));
        assertThatThrownBy(() -> draft(nested)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleExpression");
    }

    @Test
    void validatesRateAndFixedCombinations() {
        ObjectNode json = baseDraft();
        ObjectNode rule = commissionRule();
        array(json, "commissionRules").add(rule);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();

        rule.put("fixedAmount", "100");
        assertInvalid(json, "commissionRules[0].ratePct");
        rule.put("calculationType", "FIXED").remove("ratePct");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        rule.remove("fixedAmount");
        assertInvalid(json, "commissionRules[0].fixedAmount");
    }

    @Test
    void rejectsDuplicateChildIdsEvenWhenTheirIntervalsDoNotOverlap() {
        ObjectNode commission = baseDraft();
        array(commission, "commissionRules").add(commissionRule().put("commissionRuleId", 7))
                .add(commissionRule().put("commissionRuleId", 7).put("installmentFrom", 13).put("installmentTo", 24));
        assertInvalid(commission, "commissionRules[1].commissionRuleId");

        ObjectNode fee = baseDraft().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        array(fee, "feeRegimeRules").add(feeRule().put("feeRegimeRulesetId", 9).put("salesStartDateTo", "2027-06-30"))
                .add(feeRule().put("feeRegimeRulesetId", 9).put("salesStartDateFrom", "2027-07-01"));
        assertInvalid(fee, "feeRegimeRules[1].feeRegimeRulesetId");
    }

    @Test
    void rejectsAmbiguousCommissionIntervalsAtSubmissionIncludingDifferentComponents() {
        ObjectNode json = baseDraft();
        ObjectNode right = commissionRule().put("installmentFrom", 12).put("installmentTo", 24)
                .put("feeComponentType", "INCENTIVE");
        array(json, "commissionRules").add(commissionRule()).add(right);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateForSubmission(draft(json)))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.POL_002));
        right.put("installmentFrom", 13);
        assertThatCode(() -> validator.validateForSubmission(draft(json))).doesNotThrowAnyException();
    }

    @Test
    void permitsOverlappingCommissionRulesWithDistinctPriorityOrApplicability() {
        ObjectNode json = baseDraft();
        ObjectNode right = commissionRule().put("priorityNo", 50);
        array(json, "commissionRules").add(commissionRule()).add(right);
        assertThatCode(() -> validator.validateForSubmission(draft(json))).doesNotThrowAnyException();
        right.put("priorityNo", 100).put("insurerId", 5);
        assertThatCode(() -> validator.validateForSubmission(draft(json))).doesNotThrowAnyException();
        right.remove("insurerId");
        right.put("paymentStage", "INSURER_TO_GA");
        assertThatCode(() -> validator.validateForSubmission(draft(json))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnselectableCurrentRegimeAndDuplicateSourceDetailRef() {
        assertInvalid(baseDraft().put("feeRegimeCode", "SEVEN_YEAR_2029"), "feeRegimeCode");
        assertThatCode(() -> validator.validate(draft(baseDraft().put("feeRegimeCode", "CURRENT"))))
                .doesNotThrowAnyException();
        ObjectNode json = baseDraft();
        ObjectNode detail = MAPPER.createObjectNode().put("ref", "SRC-025").put("title", "근거");
        array(json, "sourceDetails").add(detail).add(detail.deepCopy().put("title", "다른 설명"));
        assertInvalid(json, "sourceDetails[1].ref");
    }

    @Test
    void rejectsPrecisionLossBeforeDatabaseRounding() {
        ObjectNode json = baseDraft();
        ObjectNode rule = commissionRule().put("ratePct", "0.1234567");
        array(json, "commissionRules").add(rule);
        assertInvalid(json, "commissionRules[0].ratePct");
        rule.put("ratePct", "1000");
        assertInvalid(json, "commissionRules[0].ratePct");
        rule.put("ratePct", "999.999999");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();

        rule.put("calculationType", "FIXED").put("fixedAmount", "100.001").remove("ratePct");
        assertInvalid(json, "commissionRules[0].fixedAmount");
        rule.put("fixedAmount", "10000000000000");
        assertInvalid(json, "commissionRules[0].fixedAmount");
        rule.put("fixedAmount", "9999999999999.99");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedBasisAndInvalidInstallments() {
        ObjectNode json = baseDraft();
        ObjectNode rule = commissionRule().put("basisCode", "ARBITRARY_SQL");
        array(json, "commissionRules").add(rule);
        assertInvalid(json, "commissionRules[0].basisCode");
        rule.put("basisCode", "MONTHLY_EQUIVALENT_FIRST_PREMIUM").put("installmentFrom", 3).put("installmentTo", 2);
        assertInvalid(json, "commissionRules[0].installmentTo");
        rule.put("installmentTo", 85);
        assertInvalid(json, "commissionRules[0].installmentTo");
    }

    @Test
    void requiresFourYearHeaderAndValidChildRegime() {
        ObjectNode json = baseDraft().put("policyType", "FOUR_YEAR_COMMISSION");
        assertInvalid(json, "feeRegimeCode");
        json.put("feeRegimeCode", "FOUR_YEAR_2027");
        ObjectNode rule = feeRule().put("maxInstallments", 49);
        array(json, "feeRegimeRules").add(rule);
        assertInvalid(json, "feeRegimeRules[0].maxInstallments");
        rule.put("maxInstallments", 48).put("longTermMaintenanceFrom", 40).put("longTermMaintenanceTo", 48);
        assertInvalid(json, "feeRegimeRules[0].maxInstallments");
        rule.remove("longTermMaintenanceFrom");
        rule.remove("longTermMaintenanceTo");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        rule.put("feeRegimeCode", "SEVEN_YEAR_2029");
        assertInvalid(json, "feeRegimeRules[0].feeRegimeCode");
    }

    @Test
    void validatesSevenYearLongTermBoundsWithoutInventingStartingInstallment() {
        ObjectNode json = baseDraft().put("policyType", "SEVEN_YEAR_COMMISSION").put("feeRegimeCode", "SEVEN_YEAR_2029");
        ObjectNode rule = feeRule().put("feeRegimeCode", "SEVEN_YEAR_2029").put("maxInstallments", 84)
                .put("longTermMaintenanceFrom", 49);
        array(json, "feeRegimeRules").add(rule);
        assertInvalid(json, "feeRegimeRules[0].longTermMaintenanceTo");
        rule.put("longTermMaintenanceTo", 85);
        assertInvalid(json, "feeRegimeRules[0].longTermMaintenanceTo");
        rule.put("longTermMaintenanceTo", 84);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        rule.put("longTermMaintenanceFrom", 61);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
    }

    @Test
    void validatesFeeDocumentVersionAgainstHeaderBeforeDatabaseTrigger() {
        ObjectNode json = baseDraft().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027")
                .put("basicDocumentVersion", "V1");
        ObjectNode rule = feeRule().put("basicDocumentVersion", "V2");
        array(json, "feeRegimeRules").add(rule);
        assertInvalid(json, "feeRegimeRules[0].basicDocumentVersion");
        rule.put("basicDocumentVersion", "V1");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        rule.remove("basicDocumentVersion");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
    }

    @Test
    void rejectsFeeOverlapAtInclusiveBoundaryWith422() {
        ObjectNode json = baseDraft().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        array(json, "feeRegimeRules").add(feeRule().put("salesStartDateTo", "2027-06-30"))
                .add(feeRule().put("salesStartDateFrom", "2027-06-30"));
        assertThatThrownBy(() -> validator.validate(draft(json))).isInstanceOfSatisfying(FgcBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.POL_002));
    }

    @Test
    void allowsSeparateDocumentIntervalsAndVersionsWithinSameSalesInterval() {
        ObjectNode json = baseDraft().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        ObjectNode left = feeRule().put("basicDocumentDateTo", "2026-06-30");
        ObjectNode right = feeRule().put("basicDocumentDateFrom", "2026-07-01");
        array(json, "feeRegimeRules").add(left).add(right);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        right.remove("basicDocumentDateFrom");
        right.put("basicDocumentVersion", "V2");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
    }

    @Test
    void onlyAcceptsImplementedScheduleParametersForCorrectPolicyType() {
        ObjectNode json = baseDraft().put("policyType", "SCHEDULE_ELIGIBILITY");
        ObjectNode parameter = MAPPER.createObjectNode().put("key", "STATUS_MONTH_RULE").put("value", "PAY_IF_ACTIVE_ON_DUE_DATE");
        array(json, "parameters").add(parameter);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        parameter.put("value", "EXECUTE_ARBITRARY_RULE");
        assertInvalid(json, "parameters[0].value");
        parameter.put("value", "PAY_IF_ACTIVE_ON_DUE_DATE");
        json.put("policyType", "CURRENT_COMMISSION");
        assertInvalid(json, "parameters[0].value");
    }

    @Test
    void enforcesArbitrageValueTypesAndExistingBounds() {
        ObjectNode json = baseDraft().put("policyType", "ARBITRAGE");
        ObjectNode parameter = MAPPER.createObjectNode().put("key", "ARBITRAGE_REFUND_ADDITION_MONTHS").put("value", 36);
        array(json, "parameters").add(parameter);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        parameter.put("value", "36");
        assertInvalid(json, "parameters[0].value");
        parameter.put("value", 1201);
        assertInvalid(json, "parameters[0].value");
        parameter.put("key", "ARBITRAGE_REFUND_ADDITION_BOUNDARY_INCLUSIVE").put("value", false);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        parameter.put("key", "ARBITRAGE_EFFECTIVE_CONTRACT_DATE").put("value", "2026-02-30");
        assertInvalid(json, "parameters[0].value");
    }

    @Test
    void rejectsDuplicateParameterKeysAndNonzeroTolerance() {
        ObjectNode json = baseDraft().put("policyType", "RECONCILIATION_TOLERANCE");
        ObjectNode parameter = MAPPER.createObjectNode().put("key", "TOLERANCE_AMOUNT_KRW").put("value", 1);
        array(json, "parameters").add(parameter);
        assertInvalid(json, "parameters[0].value");
        parameter.put("value", 0);
        array(json, "parameters").add(parameter.deepCopy());
        assertInvalid(json, "parameters[1].key");
    }

    @Test
    void validatesSourceDetailsAndRefusesUndefinedClawbackRules() {
        ObjectNode json = baseDraft();
        ObjectNode source = MAPPER.createObjectNode().put("ref", "SRC-025 Q11").put("title", "승인 근거")
                .put("explanation", "적용 이유").put("url", "https://example.test/evidence");
        array(json, "sourceDetails").add(source);
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        source.put("title", "x".repeat(201));
        assertInvalid(json, "sourceDetails[0].title");
        array(json, "sourceDetails").removeAll();
        json.put("policyType", "CLAWBACK");
        array(json, "clawbackRules").add(MAPPER.createObjectNode().put("elapsedMonthFrom", 0)
                .put("elapsedMonthTo", 12).put("clawbackBasisCode", "UNCONFIRMED").put("clawbackRatePct", 50));
        assertInvalid(json, "clawbackRules[0].clawbackBasisCode");
        ObjectNode rule = (ObjectNode) array(json, "clawbackRules").get(0);
        rule.put("clawbackBasisCode", "PAID_ATTRIBUTED_AMOUNT");
        rule.putNull("conditionExpression");
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        rule.put("elapsedMonthTo", -1);
        assertInvalid(json, "clawbackRules[0].elapsedMonthTo");
        rule.put("elapsedMonthTo", 12).put("clawbackRatePct", "100.000001");
        assertInvalid(json, "clawbackRules[0].clawbackRatePct");
        rule.put("clawbackRatePct", "33.333333");
        rule.putObject("conditionExpression").put("arbitrary", true);
        assertInvalid(json, "clawbackRules[0].conditionExpression");
        rule.putObject("conditionExpression");
        array(json, "clawbackRules").add(rule.deepCopy().put("elapsedMonthFrom", 12).put("elapsedMonthTo", 24));
        assertThatThrownBy(() -> validator.validate(draft(json))).isInstanceOf(FgcBusinessException.class);
    }

    @Test
    void validatesActionAndCloneReasonsAndExpectedState() {
        PolicyActionRequest action = MAPPER.convertValue(MAPPER.createObjectNode().put("actionType", "SUBMIT")
                .put("reason", "  ").put("expectedStatus", "DRAFT"), PolicyActionRequest.class);
        assertThat(FACTORY.getValidator().validate(action)).extracting(v -> v.getPropertyPath().toString())
                .contains("reason", "expectedUpdatedAt");
        PolicyCloneRequest clone = MAPPER.convertValue(MAPPER.createObjectNode().put("effectiveFrom", "2027-01-01")
                .put("effectiveTo", "2026-12-31").put("reason", "새 판"), PolicyCloneRequest.class);
        assertThat(FACTORY.getValidator().validate(clone)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("validPeriod");
        assertThat(MAPPER.valueToTree(clone).has("validPeriod")).isFalse();
    }

    @Test
    void submissionRequiresEveryCommissionToFitAllApplicableRegimeLimits() {
        ObjectNode json = baseDraft().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        ObjectNode commission = commissionRule().put("installmentTo", 60);
        array(json, "commissionRules").add(commission);
        array(json, "feeRegimeRules").add(feeRule());
        assertThatCode(() -> validator.validate(draft(json))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateForSubmission(draft(json)))
                .isInstanceOfSatisfying(FgcBusinessException.class, e ->
                        assertThat(e.getField()).isEqualTo("commissionRules[0].installmentTo"));
        commission.put("installmentTo", 48);
        assertThatCode(() -> validator.validateForSubmission(draft(json))).doesNotThrowAnyException();
        ((ObjectNode) json.path("feeRegimeRules").get(0)).put("salesStartDateTo", "2027-12-31");
        array(json, "feeRegimeRules").add(feeRule().put("salesStartDateFrom", "2028-01-01").put("maxInstallments", 36));
        assertThatThrownBy(() -> validator.validateForSubmission(draft(json)))
                .isInstanceOfSatisfying(FgcBusinessException.class, e ->
                        assertThat(e.getField()).isEqualTo("commissionRules[0].installmentTo"));
    }

    @Test
    void submissionRejectsFourYearLongTermAndRequiresSevenYearLongTermRange() {
        ObjectNode json = baseDraft().put("policyType", "FOUR_YEAR_COMMISSION").put("feeRegimeCode", "FOUR_YEAR_2027");
        ObjectNode commission = commissionRule().put("feeComponentType", "LONG_TERM_MAINTENANCE");
        ObjectNode regime = feeRule();
        array(json, "commissionRules").add(commission);
        array(json, "feeRegimeRules").add(regime);
        assertThatThrownBy(() -> validator.validateForSubmission(draft(json)))
                .isInstanceOfSatisfying(FgcBusinessException.class, e ->
                        assertThat(e.getField()).isEqualTo("commissionRules[0].feeComponentType"));
        json.put("policyType", "SEVEN_YEAR_COMMISSION").put("feeRegimeCode", "SEVEN_YEAR_2029");
        regime.put("feeRegimeCode", "SEVEN_YEAR_2029").put("maxInstallments", 84)
                .put("longTermMaintenanceFrom", 61).put("longTermMaintenanceTo", 84);
        commission.put("installmentFrom", 60).put("installmentTo", 84);
        assertThatThrownBy(() -> validator.validateForSubmission(draft(json)))
                .isInstanceOfSatisfying(FgcBusinessException.class, e ->
                        assertThat(e.getField()).isEqualTo("commissionRules[0].installmentFrom"));
        commission.put("installmentFrom", 61);
        assertThatCode(() -> validator.validateForSubmission(draft(json))).doesNotThrowAnyException();
    }

    private void assertInvalid(ObjectNode json, String field) {
        assertThatThrownBy(() -> validator.validate(draft(json)))
                .isInstanceOfSatisfying(FgcBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002);
                    assertThat(e.getField()).startsWith(field);
                });
    }

    private static PolicyDraft draft(ObjectNode json) {
        return MAPPER.convertValue(json, PolicyDraft.class);
    }

    private static ObjectNode baseDraft() {
        ObjectNode json = MAPPER.createObjectNode().put("policyCode", "TEST-DRAFT").put("policyName", "정책 초안")
                .put("policyType", "CURRENT_COMMISSION").put("sourceClass", "GA_POLICY")
                .put("effectiveFrom", "2026-01-01");
        for (String field : new String[]{"regulationRefs", "sourceRefs", "sourceDetails", "commissionRules",
                "feeRegimeRules", "parameters", "clawbackRules"}) json.putArray(field);
        return json;
    }

    private static ObjectNode commissionRule() {
        return MAPPER.createObjectNode().put("paymentStage", "GA_TO_FC").put("commissionItemId", 1)
                .put("feeComponentType", "CURRENT").put("installmentFrom", 1).put("installmentTo", 12)
                .put("calculationType", "RATE").put("basisCode", "MONTHLY_EQUIVALENT_FIRST_PREMIUM")
                .put("ratePct", "50.000000").put("priorityNo", 100);
    }

    private static ObjectNode feeRule() {
        ObjectNode rule = MAPPER.createObjectNode().put("feeRegimeCode", "FOUR_YEAR_2027")
                .put("salesStartDateFrom", "2027-01-01").put("maxInstallments", 48);
        rule.putArray("sourceRefs");
        return rule;
    }

    private static ArrayNode array(ObjectNode json, String field) {
        return (ArrayNode) json.get(field);
    }
}
