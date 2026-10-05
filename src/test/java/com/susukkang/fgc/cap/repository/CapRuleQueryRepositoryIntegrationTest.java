package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapRuleItemView;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : PostgreSQL에서 cap 규칙의 선택 순서·적용범위와 JPQL DTO projection을 검증한다.
 *
 * @author hjKang
 * @since 2026-10-02
 * @version 1.0
 */
@SpringBootTest
@Transactional
class CapRuleQueryRepositoryIntegrationTest {

    // 데모 규칙의 적용 시작일(2021년)보다 앞선 기간으로 조회 후보를 격리한다.
    private static final LocalDate FROM = LocalDate.of(2000, 1, 1);
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2000, 1, 15);
    private static final String STAGE = "INSURER_TO_GA";
    private static final String PRODUCT_GROUP = "TEST_CAP_GROUP";
    private static final String CHANNEL = "TEST_CAP_CHANNEL";

    @Autowired CapRuleQueryRepository capRuleQueryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long policyId;
    private Long insurerId;

    @BeforeEach
    void insertDraftPolicy() {
        insurerId = jdbcTemplate.queryForObject("SELECT min(insurer_id) FROM fgc.insurer", Long.class);
        policyId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, status, created_by, approved_by, approved_at
                )
                SELECT ?, '한도 규칙 조회 테스트', 'CAP_1200', 'PROJECT_ASSUMPTION', 1,
                       DATE '2000-01-01', 'DRAFT', created_by, approved_by, approved_at
                  FROM fgc.policy_version
                 WHERE status = 'ACTIVE' AND created_by IS NOT NULL AND approved_by IS NOT NULL
                 ORDER BY policy_version_id
                 LIMIT 1
                RETURNING policy_version_id
                """, Long.class, "TEST-CAP-RULE-" + UUID.randomUUID());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    void prefersMoreSpecificScopeOverNewerRules(int scope) {
        Long expectedId = insertRule(STAGE, FROM, null, scope);
        for (int broaderScope = 0; broaderScope < 8; broaderScope++) {
            if (Integer.bitCount(broaderScope) < Integer.bitCount(scope)) {
                insertRule(STAGE, FROM.plusDays(1), null, broaderScope);
            }
        }
        setPolicyStatus("ACTIVE");

        assertThat(findRule(CONTRACT_DATE).getCapRuleSetId()).isEqualTo(expectedId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void breaksSpecificityTiesByStartDateThenId(boolean sameStartDate) {
        Long earlierId = insertRule(STAGE, FROM.plusDays(1), null, 7);
        Long laterId = insertRule(STAGE, sameStartDate ? FROM.plusDays(1) : FROM,
                FROM.plusYears(1), 7);
        setPolicyStatus("ACTIVE");

        assertThat(findRule(CONTRACT_DATE).getCapRuleSetId())
                .isEqualTo(sameStartDate ? laterId : earlierId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW", "APPROVED", "RETIRED", "STAGE",
            "INSURER", "PRODUCT_GROUP", "CHANNEL", "NULL_INSURER", "NULL_PRODUCT_GROUP", "NULL_CHANNEL"})
    void rejectsInactivePoliciesAndNonmatchingScope(String mismatch) {
        insertRule(STAGE, FROM, null, 7);
        setPolicyStatus(List.of("DRAFT", "REVIEW", "APPROVED", "RETIRED").contains(mismatch)
                ? mismatch : "ACTIVE");

        CapRuleSetView actual = capRuleQueryRepository.findApplicableRuleSet(
                mismatch.equals("STAGE") ? "GA_TO_FC" : STAGE,
                CONTRACT_DATE,
                switch (mismatch) {
                    case "INSURER" -> -1L;
                    case "NULL_INSURER" -> null;
                    default -> insurerId;
                },
                switch (mismatch) {
                    case "PRODUCT_GROUP" -> "OTHER_GROUP";
                    case "NULL_PRODUCT_GROUP" -> null;
                    default -> PRODUCT_GROUP;
                },
                switch (mismatch) {
                    case "CHANNEL" -> "OTHER_CHANNEL";
                    case "NULL_CHANNEL" -> null;
                    default -> CHANNEL;
                });

        assertThat(actual).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "2000-01-01, 2000-01-31, true",
            "2000-01-31, 2000-01-31, true",
            "1999-12-31, 2000-01-31, false",
            "2000-02-01, 2000-01-31, false",
            "2001-01-01, , true"
    })
    void usesInclusiveRuleDatesAndAllowsOpenEnd(String contractDate, String dateTo, boolean applicable) {
        Long expectedId = insertRule(STAGE, FROM, dateTo == null ? null : LocalDate.parse(dateTo), 0);
        setPolicyStatus("ACTIVE");

        CapRuleSetView actual = findRule(LocalDate.parse(contractDate));

        if (applicable) {
            assertThat(actual).isNotNull();
            assertThat(actual.getCapRuleSetId()).isEqualTo(expectedId);
        } else {
            assertThat(actual).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mapsEveryRuleSetFieldIncludingNullableScopeAndEnd(boolean includeNullableValues) {
        LocalDate dateTo = includeNullableValues ? FROM.plusYears(1) : null;
        Long ruleId = insertRule(STAGE, FROM, dateTo, includeNullableValues ? 7 : 0);
        setPolicyStatus("ACTIVE");

        CapRuleSetView expected = new CapRuleSetView(
                ruleId, policyId, STAGE, FROM, dateTo,
                includeNullableValues ? insurerId : null,
                includeNullableValues ? PRODUCT_GROUP : null,
                includeNullableValues ? CHANNEL : null,
                12, new BigDecimal("13.2500"), new BigDecimal("2.5000"),
                "STANDARD_DEDUCTION_80", new BigDecimal("87.5000"));

        assertThat(findRule(CONTRACT_DATE)).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    void wildcardScopeAlsoMatchesNullLookupParameters() {
        Long expectedId = insertRule(STAGE, FROM, null, 0);
        insertRule(STAGE, FROM, null, 7);
        setPolicyStatus("ACTIVE");

        CapRuleSetView actual = capRuleQueryRepository.findApplicableRuleSet(
                STAGE, CONTRACT_DATE, null, null, null);

        assertThat(actual.getCapRuleSetId()).isEqualTo(expectedId);
    }

    @Test
    void mapsRuleItemsAndOnlyReturnsItemsFromRequestedRuleSet() {
        Long selectedRule = insertRule(STAGE, FROM, null, 0);
        Long otherRule = insertRule("GA_TO_FC", FROM, null, 0);
        Long baseItemId = commissionItemId("BASE_COMMISSION");
        Long supportItemId = commissionItemId("NEWCOMER_SUPPORT");
        Long includedId = insertRuleItem(selectedRule, baseItemId, false);
        Long excludedId = insertRuleItem(selectedRule, supportItemId, true);
        insertRuleItem(otherRule, baseItemId, true);
        setPolicyStatus("ACTIVE");

        List<CapRuleItemView> expected = List.of(
                new CapRuleItemView(includedId, baseItemId, "BASE_COMMISSION", "FC 기본수수료",
                        "INCLUDED", null, false, "DIRECT", "산입 조회 테스트"),
                new CapRuleItemView(excludedId, supportItemId, "NEWCOMER_SUPPORT", "신인활동지원비",
                        "EXCLUDED", "NEWCOMER_SUPPORT", true, "NOT_APPLICABLE", "제외 조회 테스트"));

        assertThat(capRuleQueryRepository.findRuleItems(selectedRule))
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(capRuleQueryRepository.findRuleItems(otherRule)).hasSize(1);
        assertThat(capRuleQueryRepository.findRuleItems(-1L)).isEmpty();
    }

    @Test
    void returnsEmptyItemsForExistingRuleWithoutItems() {
        Long ruleId = insertRule(STAGE, FROM, null, 0);
        setPolicyStatus("ACTIVE");

        assertThat(capRuleQueryRepository.findRuleItems(ruleId)).isEmpty();
    }

    private CapRuleSetView findRule(LocalDate contractDate) {
        return capRuleQueryRepository.findApplicableRuleSet(
                STAGE, contractDate, insurerId, PRODUCT_GROUP, CHANNEL);
    }

    // 비트 1·2·4는 보험사·상품군·채널이 각각 지정되었음을 나타낸다.
    private Long insertRule(String stage, LocalDate from, LocalDate to, int scope) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_rule_set (
                    policy_version_id, payment_stage, contract_date_from, contract_date_to,
                    insurer_id, product_group_code, channel_code, first_year_months,
                    premium_multiplier, compliance_deduction_pct, refund_addition_condition, warning_usage_pct
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 12, 13.2500, ?, 'STANDARD_DEDUCTION_80', 87.5000)
                RETURNING cap_rule_set_id
                """, Long.class, policyId, stage, from, to,
                (scope & 1) == 0 ? null : insurerId,
                (scope & 2) == 0 ? null : PRODUCT_GROUP,
                (scope & 4) == 0 ? null : CHANNEL,
                stage.equals(STAGE) ? new BigDecimal("2.5000") : BigDecimal.ZERO);
    }

    private Long commissionItemId(String code) {
        return jdbcTemplate.queryForObject("""
                SELECT commission_item_id FROM fgc.commission_item WHERE item_code = ?
                """, Long.class, code);
    }

    private Long insertRuleItem(Long ruleId, Long commissionItemId, boolean excluded) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_rule_item (
                    cap_rule_set_id, commission_item_id, inclusion_status, exclusion_type,
                    evidence_required_yn, attribution_method, decision_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING cap_rule_item_id
                """, Long.class, ruleId, commissionItemId,
                excluded ? "EXCLUDED" : "INCLUDED", excluded ? "NEWCOMER_SUPPORT" : null,
                excluded, excluded ? "NOT_APPLICABLE" : "DIRECT",
                excluded ? "제외 조회 테스트" : "산입 조회 테스트");
    }

    private void setPolicyStatus(String status) {
        if (status.equals("DRAFT")) {
            return;
        }
        if (status.equals("REVIEW")) {
            jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'REVIEW' WHERE policy_version_id = ?", policyId);
            return;
        }
        jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'APPROVED' WHERE policy_version_id = ?", policyId);
        if (!status.equals("APPROVED")) {
            jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", policyId);
        }
        if (status.equals("RETIRED")) {
            jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'RETIRED' WHERE policy_version_id = ?", policyId);
        }
    }
}
