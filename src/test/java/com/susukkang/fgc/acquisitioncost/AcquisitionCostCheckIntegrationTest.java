package com.susukkang.fgc.acquisitioncost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.policy.code.FeeRegimeResolutionStatus;
import com.susukkang.fgc.policy.service.FeeRegimeRulesetService;
import com.susukkang.fgc.policy.service.FeeRegimeSchedulePolicyService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 후순위062 인수조건: 실제 PostgreSQL과 HTTP로 두 한도 판정, 자료 부족, 적용례, 제외 증빙, 멱등, 권한, 이력 보존을 검증한다.
 * 데모 시드 G1 계약과 V65 규제 상한 룰셋을 쓴다. 기준율 3000과 금액은 시드명세 §34의 가정값이며 규정 수치가 아니다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_acquisition_651?currentSchema=fgc")
@AutoConfigureMockMvc
@Transactional
class AcquisitionCostCheckIntegrationTest {
    private static final String URL = "/api/v1/acquisition-cost-checks";
    private static final String STAGE = "GA_TO_FC";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private FeeRegimeRulesetService rulesets;
    @Autowired private FeeRegimeSchedulePolicyService schedulePolicies;

    private long contractId;
    private FgcUserDetails settlement;
    private long agentId;

    @BeforeEach
    void fourYearContract() {
        contractId = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202703-0001'", Long.class);
        agentId = jdbc.queryForObject("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId);
        jdbc.update("UPDATE fgc.insurance_contract SET monthly_equivalent_first_premium = 100000 WHERE contract_id = ?", contractId);
        regime("2027-03-02", "2027-01-01", "BD-2027-01", "2026-12-01", "FOUR_YEAR_2027");
        settlement = insertUser("SETTLEMENT");
    }

    // ── 두 한도와 경계 ─────────────────────────────────────────────────────

    @Test
    void readsLimitFromRulesetAndBaseFromPolicyParameterAndStoresEachUsage() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "600000");
        for (int i = 1; i <= 48; i++) scheduleLine(header, i, "MAINTENANCE_COMMISSION", policy, "MAINTENANCE", "45000");

        JsonNode data = data(check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.resultStatusLabel").value("한도 이내"))
                .andExpect(jsonPath("$.data.acquisitionCost.amount").value(3000000))
                .andExpect(jsonPath("$.data.acquisitionCost.premiumAmount").value(100000.0))
                .andExpect(jsonPath("$.data.acquisitionCost.ratePct").value(3000))
                .andExpect(jsonPath("$.data.acquisitionCost.description").value("가상 GA 정책(PROJECT_ASSUMPTION)"))
                .andExpect(jsonPath("$.data.upfront.limitPct").value(100.0))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(600000))
                .andExpect(jsonPath("$.data.upfront.limitAmount").value(3000000))
                .andExpect(jsonPath("$.data.upfront.usagePct").value(20.0))
                .andExpect(jsonPath("$.data.maintenance.calculatedAmount").value(2160000))
                .andExpect(jsonPath("$.data.maintenance.usagePct").value(72.0))
                .andExpect(jsonPath("$.data.indirectSupportAmount").value(0))
                .andExpect(jsonPath("$.data.limitPolicy.policyCode").value("REG-FOUR-YEAR-2027-V1"))
                .andExpect(jsonPath("$.data.limitPolicy.sourceClass").value("REGULATORY"))
                .andExpect(jsonPath("$.data.paymentPolicy.policyVersionId").value(policy))
                .andExpect(jsonPath("$.data.paymentPolicy.sourceClass").value("GA_POLICY"))
                .andExpect(jsonPath("$.data.scheduleHeaderId").value(header))
                .andReturn().getResponse().getContentAsString());

        long id = data.path("acquisitionCostCheckId").asLong();
        var parent = jdbc.queryForMap("SELECT * FROM fgc.acquisition_cost_check WHERE acquisition_cost_check_id = ?", id);
        assertThat(parent.get("validation_run_id")).isNull();
        assertThat(parent.get("payment_stage")).isEqualTo(STAGE);
        assertThat((BigDecimal) parent.get("upfront_limit_amount")).isEqualByComparingTo("3000000");
        assertThat((BigDecimal) parent.get("maintenance_total_amount")).isEqualByComparingTo("2160000");
        assertThat(parent.get("requested_by")).isEqualTo(settlement.getUserId());
        assertThat(jdbc.queryForList("""
                SELECT check_type, usage_pct, result_status FROM fgc.acquisition_cost_check_detail
                 WHERE acquisition_cost_check_id = ? ORDER BY check_type
                """, id)).hasSize(2).satisfiesExactly(
                row -> {
                    assertThat(row.get("check_type")).isEqualTo("MAINTENANCE_TOTAL");
                    assertThat((BigDecimal) row.get("usage_pct")).isEqualByComparingTo("72");
                },
                row -> {
                    assertThat(row.get("check_type")).isEqualTo("UPFRONT_TOTAL");
                    assertThat((BigDecimal) row.get("usage_pct")).isEqualByComparingTo("20");
                });
        // 저장한 입력을 그대로 다시 읽는다. 뒤에 파라미터가 사라져도 결과는 바뀌지 않는다.
        mvc.perform(get(URL + "/{id}", id).with(user(settlement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acquisitionCost.amount").value(3000000))
                .andExpect(jsonPath("$.data.calcRuleVersion").value("acq-cost-v1"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log WHERE action_code = 'ACQUISITION_COST_CHECKED' AND entity_id = ?",
                Long.class, String.valueOf(id))).isEqualTo(1);
    }

    @Test
    void equalToLimitOnBothIsNormalAndOneWonOverIsAViolationOnThatLimitOnly() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "3000000");
        scheduleLine(header, 1, "MAINTENANCE_COMMISSION", policy, "MAINTENANCE", "3000000");
        check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.upfront.usagePct").value(100.0))
                .andExpect(jsonPath("$.data.maintenance.usagePct").value(100.0));

        scheduleLine(header, 2, "BASE_COMMISSION", policy, "UPFRONT", "1");
        check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("VIOLATION"))
                .andExpect(jsonPath("$.data.upfront.status").value("VIOLATION"))
                .andExpect(jsonPath("$.data.upfront.exceededFromInstallmentNo").value(2))
                .andExpect(jsonPath("$.data.maintenance.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.maintenance.limitAmount").value(3000000));
    }

    @Test
    void indirectSupportCountsInMaintenanceTotalWithoutAnyNineteenPercentCap() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "MAINTENANCE_COMMISSION", policy, "MAINTENANCE", "2000000");
        scheduleLine(header, 1, "COMMON_COST", policy, "COMMON_COST", "900000");
        JsonNode normal = data(check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.indirectSupportAmount").value(900000))
                .andExpect(jsonPath("$.data.maintenance.calculatedAmount").value(2900000))
                .andReturn().getResponse().getContentAsString());
        assertThat((BigDecimal) jdbc.queryForObject("SELECT indirect_support_amount FROM fgc.acquisition_cost_check WHERE acquisition_cost_check_id = ?",
                BigDecimal.class, normal.path("acquisitionCostCheckId").asLong())).isEqualByComparingTo("900000");

        scheduleLine(header, 2, "COMMON_COST", policy, "COMMON_COST", "100001");
        check().andExpect(jsonPath("$.data.resultStatus").value("VIOLATION"))
                .andExpect(jsonPath("$.data.maintenance.status").value("VIOLATION"))
                .andExpect(jsonPath("$.data.maintenance.calculatedAmount").value(3000001));
    }

    // ── 자료 부족과 정상 0 ─────────────────────────────────────────────────

    @Test
    void missingAcquisitionCostParameterIsReviewRequiredWithNullLimitsNotZero() throws Exception {
        long policy = paymentPolicy(null);
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "600000");

        JsonNode data = data(check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.acquisitionCost.amount").value(nullValue()))
                .andExpect(jsonPath("$.data.upfront.limitAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.upfront.usagePct").value(nullValue()))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(600000))
                .andExpect(jsonPath("$.data.reasons[0].code").value("ACQUISITION_COST_MISSING"))
                .andReturn().getResponse().getContentAsString());

        var parent = jdbc.queryForMap("SELECT upfront_limit_amount, maintenance_limit_amount FROM fgc.acquisition_cost_check WHERE acquisition_cost_check_id = ?",
                data.path("acquisitionCostCheckId").asLong());
        assertThat(parent.get("upfront_limit_amount")).isNull();
        assertThat(parent.get("maintenance_limit_amount")).isNull();
    }

    @Test
    void noPaymentPolicyKeepsTheLimitRateButNoAmount() throws Exception {
        check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.reasons[0].code").value("RULESET_MISSING"))
                .andExpect(jsonPath("$.data.reasons[0].message").value(org.hamcrest.Matchers.containsString("지급 정책을 고르지 못했습니다")))
                .andExpect(jsonPath("$.data.limitPolicy.policyCode").value("REG-FOUR-YEAR-2027-V1"))
                .andExpect(jsonPath("$.data.paymentPolicy").value(nullValue()))
                .andExpect(jsonPath("$.data.acquisitionCost").value(nullValue()))
                .andExpect(jsonPath("$.data.upfront.limitPct").value(100.0))
                .andExpect(jsonPath("$.data.upfront.limitAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(nullValue()));
    }

    @Test
    void missingPremiumAndUnsupportedBasisDoNotInventABase() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "600000");
        jdbc.update("UPDATE fgc.insurance_contract SET monthly_equivalent_first_premium = 0 WHERE contract_id = ?", contractId);
        check().andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.acquisitionCost.amount").value(nullValue()))
                .andExpect(jsonPath("$.data.reasons[0].code").value("PREMIUM_MISSING"));
    }

    @Test
    void noActiveScheduleKeepsCalculatedAmountNullButAnEmptyBucketIsARealZero() throws Exception {
        long policy = paymentPolicy("3000");
        check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.maintenance.calculatedAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.indirectSupportAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.upfront.limitAmount").value(3000000))
                .andExpect(jsonPath("$.data.reasons[0].code").value("SCHEDULE_MISSING"));

        long header = schedule(policy);
        scheduleLine(header, 1, "MAINTENANCE_COMMISSION", policy, "MAINTENANCE", "45000");
        check().andExpect(jsonPath("$.data.resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(0))
                .andExpect(jsonPath("$.data.upfront.status").value("NORMAL"));
    }

    @Test
    void scheduleOfAnotherPaymentStageOrScenarioIsNotCounted() throws Exception {
        long policy = paymentPolicy("3000");
        long other = jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime, status, active_yn)
                VALUES (?, ?, ?, 1, 'COMPARISON', 'SCH-07', 'FOUR_YEAR_2027', 'PLANNED', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, STAGE, policy);
        scheduleLine(other, 1, "BASE_COMMISSION", policy, "UPFRONT", "9999999");
        check().andExpect(jsonPath("$.data.reasons[0].code").value("SCHEDULE_MISSING"))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(nullValue()));
    }

    @Test
    void lineWithUnknownComponentForcesReviewAndStaysOutOfTheTotals() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "100");
        scheduleLine(header, 1, "INCENTIVE", policy, "INCENTIVE", "70");
        check().andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.unclassifiedAmount").value(70))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(100))
                .andExpect(jsonPath("$.data.reasons[0].code").value("UNCLASSIFIED_ITEM"));
    }

    // ── 적용 대상 ─────────────────────────────────────────────────────────

    @Test
    void preRegimeProductIsNotApplicableWithAllAmountsNull() throws Exception {
        regime("2027-03-02", "2026-07-01", "BD-2026-07", "2026-07-01", "CURRENT");
        JsonNode data = data(check().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resultStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.resultStatusLabel").value("적용 대상 아님"))
                .andExpect(jsonPath("$.data.upfront").value(nullValue()))
                .andExpect(jsonPath("$.data.maintenance").value(nullValue()))
                .andExpect(jsonPath("$.data.acquisitionCost").value(nullValue()))
                .andExpect(jsonPath("$.data.reasons[0].code").value("NOT_APPLICABLE"))
                .andReturn().getResponse().getContentAsString());
        long id = data.path("acquisitionCostCheckId").asLong();
        assertThat(jdbc.queryForObject("SELECT upfront_limit_amount FROM fgc.acquisition_cost_check WHERE acquisition_cost_check_id = ?", BigDecimal.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.acquisition_cost_check_detail WHERE acquisition_cost_check_id = ?", Long.class, id)).isZero();
    }

    @Test
    void savingsProductIsNotApplicableEvenWhenDeclaredFourYear() throws Exception {
        jdbc.update("""
                UPDATE fgc.product SET protection_type = 'SAVINGS'
                 WHERE product_id = (SELECT po.product_id FROM fgc.product_offering po
                                     JOIN fgc.insurance_contract c ON c.product_offering_id = po.product_offering_id
                                     WHERE c.contract_id = ?)
                """, contractId);
        check().andExpect(jsonPath("$.data.resultStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.reasons[0].code").value("NOT_PROTECTION_PRODUCT"));
    }

    @Test
    void applicationBoundariesAreNeverDecided() throws Exception {
        regime("2026-11-10", "2027-01-01", "BD-2027-01", "2027-01-01", "FOUR_YEAR_2027");
        check().andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.reasons[0].code").value("CONTRACT_OUTSIDE_SALES_PERIOD"))
                .andExpect(jsonPath("$.data.upfront").value(nullValue()));

        regime("2029-03-02", "2027-01-01", "BD-2027-01", "2026-12-01", "FOUR_YEAR_2027");
        check().andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.reasons[0].code").value("FOUR_YEAR_AFTER_SPECIAL_PERIOD"));

        regime("2027-03-02", "2027-01-01", "BD-2027-01", "2026-12-01", "CURRENT");
        check().andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.data.reasons[0].code").value("CURRENT_BUT_ELIGIBLE"));
    }

    // ── 제4-32조제7항제3호 제외 ───────────────────────────────────────────

    @Test
    void exclusionCountsOnlyWithConfirmedEvidence() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "3000000");
        long newcomer = scheduleLine(header, 1, "NEWCOMER_SUPPORT", policy, "NEWCOMER_SUPPORT", "300000");

        // 미충족: 증빙이 없으면 선지급 총액에 넣고 한도를 넘는다.
        check().andExpect(jsonPath("$.data.resultStatus").value("VIOLATION"))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(3300000))
                .andExpect(jsonPath("$.data.exclusions[0].excluded").value(false))
                .andExpect(jsonPath("$.data.reasons[0].code").value("EXCLUSION_EVIDENCE_MISSING"));

        // 확정 전(DRAFT) 지급의 증빙은 인정하지 않는다.
        long draft = excludedPayment(newcomer, "EVD-NEWCOMER-651");
        check().andExpect(jsonPath("$.data.exclusions[0].excluded").value(false));

        // 충족: 확정 지급에 증빙이 연결되면 계산에서 뺀다.
        jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", draft);
        check().andExpect(jsonPath("$.data.resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.upfront.calculatedAmount").value(3000000))
                .andExpect(jsonPath("$.data.exclusions[0].excluded").value(true))
                .andExpect(jsonPath("$.data.exclusions[0].evidenceRef").value("EVD-NEWCOMER-651"));
    }

    // ── 예정 생성과의 경계, 확정 ───────────────────────────────────────────

    @Test
    void scheduleCreationDoesNotDependOnThisCheck() {
        long policy = paymentPolicy(null);
        // 기준 파라미터와 한도 검증 결과가 없어도 예정 생성용 정책은 선택된다.
        var selected = schedulePolicies.resolve(contractId, PaymentStage.GA_TO_FC);
        assertThat(selected.isSelected()).isTrue();
        assertThat(selected.commissionPolicy().getPolicyVersionId()).isEqualTo(policy);
        assertThat(rulesets.resolveForSchedule(contractId, PaymentStage.GA_TO_FC).resolution().status())
                .isEqualTo(FeeRegimeResolutionStatus.SELECTED);
        assertThat(Arrays.stream(ScheduleService.class.getDeclaredFields()).map(f -> f.getType().getName()))
                .noneMatch(type -> type.contains(".acquisitioncost."));
        assertThat(Arrays.stream(FeeRegimeSchedulePolicyService.class.getDeclaredFields()).map(f -> f.getType().getName()))
                .noneMatch(type -> type.contains(".acquisitioncost."));
    }

    @Test
    void noFourYearLineCanBeConfirmedWhateverTheResultIs() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        long line = scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "600000");
        check().andExpect(jsonPath("$.data.resultStatus").value("NORMAL"));
        confirm(header);

        scheduleLine(header, 2, "BASE_COMMISSION", policy, "UPFRONT", "2400001");
        check().andExpect(jsonPath("$.data.resultStatus").value("VIOLATION"));
        confirm(header);

        assertThat(jdbc.queryForObject("SELECT line_status FROM fgc.schedule_line WHERE schedule_line_id = ?", String.class, line))
                .isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.schedule_header WHERE schedule_header_id = ?", String.class, header))
                .isEqualTo("PLANNED");
    }

    private void confirm(long header) throws Exception {
        mvc.perform(post("/api/v1/schedules/{id}/confirm", header).with(user(settlement)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-SCHEDULE_PREVIEW_ONLY"));
    }

    // ── 멱등, 입력, 권한, 이력 ─────────────────────────────────────────────

    @Test
    void sameKeyReplaysTheStoredResultAndConflictsOnOtherRequestOrUser() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "600000");
        String key = UUID.randomUUID().toString();

        JsonNode first = data(request(settlement, key, contractId, STAGE).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        // 원천이 바뀌어도 같은 키는 최초 결과를 돌려준다.
        scheduleLine(header, 2, "BASE_COMMISSION", policy, "UPFRONT", "1");
        JsonNode replay = data(request(settlement, key, contractId, STAGE).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.acquisition_cost_check WHERE contract_id = ?", Long.class, contractId)).isEqualTo(1);

        request(settlement, key, contractId, "INSURER_TO_GA").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-IDEMPOTENCY_CONFLICT"));
        request(insertUser("SYSTEM_ADMIN"), key, contractId, STAGE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-IDEMPOTENCY_CONFLICT"));

        // 재사용 응답도 현재 권한을 확인한다.
        request(insertUser("COMPLIANCE"), key, contractId, STAGE).andExpect(status().isForbidden());
    }

    @Test
    void newKeyAppendsANewResultAndOldResultStaysAsItWas() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "600000");
        JsonNode first = data(check().andReturn().getResponse().getContentAsString());
        scheduleLine(header, 2, "BASE_COMMISSION", policy, "UPFRONT", "1");
        JsonNode second = data(check().andReturn().getResponse().getContentAsString());
        assertThat(second.path("acquisitionCostCheckId").asLong()).isNotEqualTo(first.path("acquisitionCostCheckId").asLong());
        assertThat(second.path("upfront").path("calculatedAmount").asLong()).isEqualTo(600001);

        JsonNode reread = data(mvc.perform(get(URL + "/{id}", first.path("acquisitionCostCheckId").asLong()).with(user(settlement)))
                .andReturn().getResponse().getContentAsString());
        assertThat(reread).isEqualTo(first);
        assertThatThrownBy(() -> jdbc.update("UPDATE fgc.acquisition_cost_check SET result_status = 'NORMAL' WHERE contract_id = ?", contractId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsBadInputAndUnknownContract() throws Exception {
        mvc.perform(post(URL).with(user(settlement)).with(csrf()).contentType(APPLICATION_JSON)
                        .content(body(contractId, STAGE)))
                .andExpect(status().isBadRequest());
        request(settlement, "bad key!", contractId, STAGE).andExpect(status().isBadRequest());
        request(settlement, UUID.randomUUID().toString(), 999_999_999L, STAGE).andExpect(status().isNotFound());
        mvc.perform(post(URL).with(user(settlement)).with(csrf()).header("Idempotency-Key", "k-651").contentType(APPLICATION_JSON)
                        .content("{\"contractId\":" + contractId + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("sort", "unknown,asc").with(user(settlement))).andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("resultStatus", "UNKNOWN").with(user(settlement))).andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("size", "101").with(user(settlement))).andExpect(status().isBadRequest());
        mvc.perform(get(URL + "/{id}", 999_999_999L).with(user(settlement))).andExpect(status().isNotFound());
    }

    @Test
    void rolesAreCheckedOnTheServer() throws Exception {
        long id = data(check().andReturn().getResponse().getContentAsString()).path("acquisitionCostCheckId").asLong();

        for (String role : new String[]{"COMPLIANCE", "GA_ADMIN"}) {
            FgcUserDetails reader = insertUser(role);
            mvc.perform(get(URL).with(user(reader))).andExpect(status().isOk());
            mvc.perform(get(URL + "/{id}", id).with(user(reader))).andExpect(status().isOk());
            request(reader, UUID.randomUUID().toString(), contractId, STAGE).andExpect(status().isForbidden());
        }
        request(insertUser("SYSTEM_ADMIN"), UUID.randomUUID().toString(), contractId, STAGE).andExpect(status().isCreated());

        FgcUserDetails agent = insertUser("AGENT");
        mvc.perform(get(URL).with(user(agent))).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/{id}", id).with(user(agent))).andExpect(status().isForbidden());
        mvc.perform(get(URL).param("contractId", String.valueOf(contractId)).with(user(agent))).andExpect(status().isForbidden());
        request(agent, UUID.randomUUID().toString(), contractId, STAGE).andExpect(status().isForbidden());

        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void searchFiltersSortsAndPagesStoredResults() throws Exception {
        long policy = paymentPolicy("3000");
        long header = schedule(policy);
        scheduleLine(header, 1, "BASE_COMMISSION", policy, "UPFRONT", "600000");
        long normal = data(check().andReturn().getResponse().getContentAsString()).path("acquisitionCostCheckId").asLong();
        scheduleLine(header, 2, "BASE_COMMISSION", policy, "UPFRONT", "2400001");
        long violation = data(check().andReturn().getResponse().getContentAsString()).path("acquisitionCostCheckId").asLong();
        long otherStage = data(request(settlement, UUID.randomUUID().toString(), contractId, "INSURER_TO_GA")
                .andReturn().getResponse().getContentAsString()).path("acquisitionCostCheckId").asLong();

        mvc.perform(get(URL).param("contractId", String.valueOf(contractId)).with(user(settlement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.sort").value("checkedAt,desc"))
                .andExpect(jsonPath("$.data.content[0].acquisitionCostCheckId").value(otherStage))
                .andExpect(jsonPath("$.data.content[2].acquisitionCostCheckId").value(normal))
                .andExpect(jsonPath("$.data.content[2].checkedAt").isNotEmpty());
        mvc.perform(get(URL).param("contractNo", "fgl01-202703").param("resultStatus", "VIOLATION").with(user(settlement)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].acquisitionCostCheckId").value(violation));
        mvc.perform(get(URL).param("contractNo", "%").param("contractId", String.valueOf(contractId)).with(user(settlement)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(URL).param("contractId", String.valueOf(contractId)).param("paymentStage", "INSURER_TO_GA").with(user(settlement)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].paymentStageLabel").value("원수사→GA"));
        mvc.perform(get(URL).param("contractId", String.valueOf(contractId)).param("size", "2").param("page", "2")
                        .param("sort", "contractNo,asc").with(user(settlement)))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content[0].acquisitionCostCheckId").value(normal));
    }

    // ── 픽스처 ───────────────────────────────────────────────────────────

    private ResultActions check() throws Exception {
        return request(settlement, UUID.randomUUID().toString(), contractId, STAGE);
    }

    private ResultActions request(FgcUserDetails actor, String key, long contract, String stage) throws Exception {
        return mvc.perform(post(URL).with(user(actor)).with(csrf()).header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON).content(body(contract, stage)));
    }

    private static String body(long contract, String stage) {
        return "{\"contractId\":" + contract + ",\"paymentStage\":\"" + stage + "\"}";
    }

    private JsonNode data(String response) {
        try {
            return json.readTree(response).path("data");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void regime(String contractDate, String salesStart, String documentVersion, String documentDate, String regime) {
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = ?::date WHERE contract_id = ?", contractDate, contractId);
        jdbc.update("""
                UPDATE fgc.product_offering
                   SET fee_regime_code = ?, sales_start_date = ?::date, basic_document_version = ?, basic_document_date = ?::date
                 WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?)
                """, regime, salesStart, documentVersion, documentDate, contractId);
    }

    /** 실제 지급 정책. 규제 상한은 V65 룰셋에서 읽고 이 정책에는 한도 행을 두지 않는다. rate가 null이면 기준 파라미터가 없다. */
    private long paymentPolicy(String rate) {
        long policy = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, status, fee_regime_code)
                VALUES (?, '4년 지급 테스트 정책', 'FOUR_YEAR_COMMISSION', 'GA_POLICY', 1, DATE '2026-01-01', 'DRAFT', 'FOUR_YEAR_2027')
                RETURNING policy_version_id
                """, Long.class, "GA-651-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO fgc.fee_regime_ruleset(policy_version_id, fee_regime_code, sales_start_date_from,
                    sales_start_date_to, basic_document_version, max_installments)
                VALUES (?, 'FOUR_YEAR_2027', DATE '2027-01-01', DATE '2028-12-31', 'BD-2027-01', 48)
                """, policy);
        for (String[] rule : new String[][]{{"BASE_COMMISSION", "UPFRONT"}, {"MAINTENANCE_COMMISSION", "MAINTENANCE"},
                {"COMMON_COST", "COMMON_COST"}, {"NEWCOMER_SUPPORT", "NEWCOMER_SUPPORT"}, {"INCENTIVE", "INCENTIVE"}}) {
            jdbc.update("""
                    INSERT INTO fgc.commission_rule(policy_version_id, payment_stage, commission_item_id, fee_component_type,
                        installment_from, installment_to, calculation_type, basis_code, rate_pct)
                    VALUES (?, ?, (SELECT commission_item_id FROM fgc.commission_item WHERE item_code = ?), ?, 1, 48, 'RATE',
                        'MONTHLY_EQUIVALENT_FIRST_PREMIUM', 1)
                    """, policy, STAGE, rule[0], rule[1]);
        }
        if (rate != null) {
            jdbc.update("""
                    INSERT INTO fgc.policy_parameter(policy_version_id, parameter_key, parameter_value, description)
                    VALUES (?, 'ACQUISITION_COST_BASIS', '"MONTHLY_EQUIVALENT_FIRST_PREMIUM"'::jsonb, '기준금액'),
                           (?, 'ACQUISITION_COST_RATE_PCT', ?::jsonb, '가상 GA 정책(PROJECT_ASSUMPTION)')
                    """, policy, policy, rate);
        }
        jdbc.update("""
                UPDATE fgc.policy_version SET status = 'APPROVED', approved_at = clock_timestamp(),
                       created_by = (SELECT min(user_id) FROM fgc.app_user), approved_by = (SELECT max(user_id) FROM fgc.app_user)
                 WHERE policy_version_id = ?
                """, policy);
        jdbc.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", policy);
        return policy;
    }

    /** §4-4-3의 4년 예정판. */
    private long schedule(long policy) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime, status, active_yn)
                VALUES (?, ?, ?, 1, 'SIMULATION', ?, 'FOUR_YEAR_2027', 'PLANNED', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, STAGE, policy, "PHASE2_FOUR_YEAR_2027_" + policy);
    }

    private long scheduleLine(long header, int installment, String item, long policy, String component, String amount) {
        Long rule = jdbc.queryForObject("""
                SELECT commission_rule_id FROM fgc.commission_rule
                 WHERE policy_version_id = ? AND fee_component_type = ?
                """, Long.class, policy, component);
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type, fixed_amount,
                    expected_amount, source_commission_rule_id)
                VALUES (?, (SELECT COALESCE(max(line_no), 0) + 1 FROM fgc.schedule_line WHERE schedule_header_id = ?), ?, ?,
                    DATE '2027-03-02', (SELECT commission_item_id FROM fgc.commission_item WHERE item_code = ?), ?,
                    'FIXED_AMOUNT', ?::numeric, 'FIXED', ?::numeric, ?::numeric, ?)
                RETURNING schedule_line_id
                """, Long.class, header, header, installment, installment, item, agentId, amount, amount, amount, rule);
    }

    /** 제외 증빙이 달린 초안 지급. */
    private long excludedPayment(long scheduleLine, String evidence) {
        long item = jdbc.queryForObject("SELECT commission_item_id FROM fgc.schedule_line WHERE schedule_line_id = ?", Long.class, scheduleLine);
        BigDecimal amount = jdbc.queryForObject("SELECT expected_amount FROM fgc.schedule_line WHERE schedule_line_id = ?", BigDecimal.class, scheduleLine);
        long transaction = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, insurer_id,
                    recipient_agent_id, commission_item_id, settlement_month, paid_on, amount, cashflow_type, installment_no)
                SELECT 'GA_TO_FC', 'GA_MANUAL_PAYMENT', ?, insurer_id, ?, ?, DATE '2026-03-01', DATE '2026-03-01', ?, 'PAYMENT', 1
                  FROM fgc.insurance_contract WHERE contract_id = ?
                RETURNING commission_transaction_id
                """, Long.class, UUID.randomUUID().toString(), agentId, item, amount, contractId);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, contract_id, agent_id, schedule_line_id,
                    attribution_date, attribution_month, attributed_amount, inclusion_status_snapshot,
                    exclusion_type_snapshot, attribution_method, evidence_ref)
                VALUES (?, ?, ?, ?, DATE '2026-03-01', DATE '2026-03-01', ?, 'EXCLUDED', 'NEW_AGENT_SUPPORT', 'DIRECT', ?)
                """, transaction, contractId, agentId, scheduleLine, amount, evidence);
        return transaction;
    }

    private FgcUserDetails insertUser(String role) {
        String login = "acq651-" + UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id)
                VALUES (?, 'unused', '계약체결비용 검증 사용자', (SELECT role_id FROM fgc.app_role WHERE role_code = ?))
                RETURNING user_id
                """, Long.class, login, role);
        return new FgcUserDetails(new AppUserView(id, login, "unused", "계약체결비용 검증 사용자", role, "ACTIVE"), true, true);
    }
}
