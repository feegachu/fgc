package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.base.repository.ProductOfferingRepository;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.policy.repository.CapRuleSetRepository;
import com.susukkang.fgc.policy.repository.RefundRateTableRepository;
import com.susukkang.fgc.validation.dto.ProductOfferingSnapshotView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidationRunCreateServiceImpl이 policy_snapshot을 조립할 때 쓰는 세 JPQL 조회
 * (findActiveCapRuleSets/findActiveRefundRateTables/findActiveProductOfferings, #379에서
 * PolicySnapshotMapper(MyBatis) 대체로 새로 추가)가 원본 SQL과 같은 결과를 내는지
 * 데모 시드 데이터로 직접 검증한다. mock으로는 "WHERE pv.status=ACTIVE 조인과
 * effective_from/to 경계"가 실제로 맞는지 증명이 안 된다.
 */
@SpringBootTest
@Transactional
class PolicySnapshotQueriesIntegrationTest {

    @Autowired
    private CapRuleSetRepository capRuleSetRepository;
    @Autowired
    private RefundRateTableRepository refundRateTableRepository;
    @Autowired
    private ProductOfferingRepository productOfferingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 데모 시드(V3)가 cap_rule_set/refund_rate_table/product_offering을 전부 2026-01-01
    // 이후로 깔아 둔다 — 그 시점 이후 아무 날짜나 asOfDate로 쓸 수 있다.
    private static final LocalDate AS_OF_DATE = LocalDate.of(2026, 8, 1);

    @Test
    void findActiveCapRuleSetsMatchesRawSqlEquivalent() {
        List<CapRuleSetView> result = capRuleSetRepository.findActiveCapRuleSets(AS_OF_DATE);

        Long expectedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.cap_rule_set crs
                  JOIN fgc.policy_version pv ON pv.policy_version_id = crs.policy_version_id
                 WHERE pv.status = 'ACTIVE'
                   AND crs.contract_date_from <= ?
                   AND (crs.contract_date_to IS NULL OR crs.contract_date_to >= ?)
                """, Long.class, AS_OF_DATE, AS_OF_DATE);

        assertThat(result).hasSize(expectedCount.intValue());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(view -> {
            assertThat(view.getCapRuleSetId()).isNotNull();
            assertThat(view.getPaymentStage()).isNotBlank();
        });
        // payment_stage, cap_rule_set_id 오름차순 정렬 보존 확인
        for (int i = 1; i < result.size(); i++) {
            CapRuleSetView prev = result.get(i - 1);
            CapRuleSetView curr = result.get(i);
            int stageCompare = prev.getPaymentStage().compareTo(curr.getPaymentStage());
            assertThat(stageCompare <= 0).isTrue();
            if (stageCompare == 0) {
                assertThat(prev.getCapRuleSetId()).isLessThan(curr.getCapRuleSetId());
            }
        }
    }

    @Test
    void findActiveRefundRateTablesMatchesRawSqlEquivalent() {
        List<RefundRateTableView> result = refundRateTableRepository.findActiveRefundRateTables(AS_OF_DATE);

        Long expectedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.refund_rate_table rrt
                  JOIN fgc.policy_version pv ON pv.policy_version_id = rrt.policy_version_id
                 WHERE pv.status = 'ACTIVE'
                   AND rrt.effective_from <= ?
                   AND (rrt.effective_to IS NULL OR rrt.effective_to >= ?)
                """, Long.class, AS_OF_DATE, AS_OF_DATE);

        assertThat(result).hasSize(expectedCount.intValue());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(view -> {
            assertThat(view.getRefundRateTableId()).isNotNull();
            assertThat(view.getVersionNo()).isNotNull();
        });
    }

    @Test
    void findActiveProductOfferingsMatchesRawSqlEquivalent() {
        List<ProductOfferingSnapshotView> result = productOfferingRepository.findActiveProductOfferings(AS_OF_DATE);

        Long expectedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.product_offering
                 WHERE active_yn = true
                   AND sales_start_date <= ?
                   AND (sales_end_date IS NULL OR sales_end_date >= ?)
                """, Long.class, AS_OF_DATE, AS_OF_DATE);

        assertThat(result).hasSize(expectedCount.intValue());
        assertThat(result).isNotEmpty();
        // 2026-H1-B는 sales_end_date=2026-06-30이라 2026-08-01 기준으로는 빠져야 한다.
        Long h1bOfferingId = jdbcTemplate.queryForObject("""
                SELECT product_offering_id FROM fgc.product_offering WHERE offering_version = '2026-H1-B'
                """, Long.class);
        assertThat(result).extracting(ProductOfferingSnapshotView::getProductOfferingId)
                .doesNotContain(h1bOfferingId);
    }
}
