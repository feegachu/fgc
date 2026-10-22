package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.web.ListSort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #604 1,200% 한도 검증 목록: 허용 컬럼의 양방향 정렬이 검색된 전체 데이터에 적용되는지 확인한다. */
@SpringBootTest
@Transactional
class CapCheckQuerySortIntegrationTest {
    @Autowired CapCheckQueryRepository repository;
    @Autowired JdbcTemplate jdbc;

    /** 값이 겹치는 행과 비어 있는 행(사용률·산입금액)을 섞어 동률·빈값 순서를 함께 검증한다. */
    private void insertSortRows() {
        Long capRuleSetId = jdbc.queryForObject("SELECT cap_rule_set_id FROM fgc.cap_rule_set WHERE payment_stage = 'INSURER_TO_GA' ORDER BY cap_rule_set_id LIMIT 1", Long.class);
        for (int i = 0; i < 9; i++) {
            Long contractId = jdbc.queryForObject("""
                    INSERT INTO fgc.insurance_contract (
                        insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                        premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                        premium_conversion_rule_code, payment_cycle_code, payment_term_months, current_status, data_origin)
                    SELECT insurer_id, product_offering_id, ?, DATE '2035-01-15', agent_id, organization_id,
                           premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                           premium_conversion_rule_code, payment_cycle_code, payment_term_months, 'ACTIVE', 'MANUAL'
                      FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                    RETURNING contract_id
                    """, Long.class, "IT-CAP-SORT-" + (i % 4) + "-" + UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO fgc.cap_check (
                        contract_id, payment_stage, cap_rule_set_id, check_kind, as_of_date, base_premium_amount,
                        refund_12m_amount, compliance_deduction_amount, limit_amount, included_amount,
                        remaining_amount, usage_pct, result_status, calculation_snapshot)
                    VALUES (?, 'INSURER_TO_GA', ?, 'REALTIME', DATE '2035-01-31' + ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL', '{}'::jsonb)
                    """, contractId, capRuleSetId, i % 3, 1000 + (i % 4) * 100, i % 2 * 10, i % 3, 5000 - i * 100,
                    2000 + (i % 5) * 50, 3000 - i * 10, i % 3 == 0 ? null : 40 + (i % 4) * 5);
        }
    }

    private static final Map<String, Function<CapCheckListRow, Comparable<?>>> VALUES = Map.of(
            "contractNo", CapCheckListRow::getContractNo,
            "asOfDate", CapCheckListRow::getAsOfDate,
            "basePremiumAmount", CapCheckListRow::getBasePremiumAmount,
            "refund12mAmount", CapCheckListRow::getRefund12mAmount,
            "complianceDeductionAmount", CapCheckListRow::getComplianceDeductionAmount,
            "limitAmount", CapCheckListRow::getLimitAmount,
            "includedAmount", CapCheckListRow::getIncludedAmount,
            "remainingAmount", CapCheckListRow::getRemainingAmount,
            "usagePct", CapCheckListRow::getUsagePct);

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sortsByEachAllowedColumnInBothDirectionsWithStableTies() {
        assertThat(CapCheckQueryRepository.SORT_KEYS).containsExactlyInAnyOrderElementsOf(VALUES.keySet());
        insertSortRows();
        List<CapCheckListRow> all = repository.search(null, null, null, null, null, null, 0, 1000);
        assertThat(all).hasSizeGreaterThan(5);
        for (var column : VALUES.entrySet()) {
            for (boolean ascending : new boolean[] {true, false}) {
                // 값이 비면 방향과 관계없이 맨 뒤, 동률은 기준일·판정 ID 내림차순.
                Comparator value = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
                Comparator<CapCheckListRow> expected = (a, b) -> {
                    int byValue = Comparator.nullsLast(value).compare(column.getValue().apply(a), column.getValue().apply(b));
                    if (byValue != 0) return byValue;
                    int byDate = b.getAsOfDate().compareTo(a.getAsOfDate());
                    return byDate != 0 ? byDate : b.getCapCheckId().compareTo(a.getCapCheckId());
                };
                ListSort sort = new ListSort(column.getKey(), ascending);
                List<CapCheckListRow> sorted = repository.search(null, null, null, null, null, null, 0, 1000, sort);
                assertThat(sorted).extracting(CapCheckListRow::getCapCheckId)
                        .as("%s %s", column.getKey(), sort.direction())
                        .containsExactlyElementsOf(all.stream().sorted(expected).map(CapCheckListRow::getCapCheckId).toList());
                // 현재 페이지가 아니라 검색된 전체 데이터 기준으로 정렬한다.
                assertThat(repository.search(null, null, null, null, null, null, 3, 3, sort))
                        .extracting(CapCheckListRow::getCapCheckId)
                        .containsExactlyElementsOf(sorted.stream().skip(3).limit(3).map(CapCheckListRow::getCapCheckId).toList());
            }
        }
    }

    @Test
    void rejectsUnsupportedSort() {
        for (String sort : List.of("resultStatus,asc", "usagePct,sideways", "usagePct", "usagePct;DROP,asc")) {
            assertThatThrownBy(() -> CapCheckQueryRepository.sortOf(sort)).isInstanceOf(FgcBusinessException.class);
        }
        assertThat(CapCheckQueryRepository.sortOf(null)).isEqualTo(CapCheckQueryRepository.DEFAULT_SORT);
    }
}
