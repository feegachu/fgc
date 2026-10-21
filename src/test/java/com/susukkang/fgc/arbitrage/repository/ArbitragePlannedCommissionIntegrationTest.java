package com.susukkang.fgc.arbitrage.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

/** #481: 지급예정 모니터링은 효력이 발생한 해지·실효 이후 회차를 합산하지 않는다. */
@SpringBootTest
@Transactional
class ArbitragePlannedCommissionIntegrationTest {
    @Autowired ArbitrageCheckRepository arbitrageCheckRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TERMINATED", "LAPSED", "MATURED", "CANCELLED"})
    void excludesOnlyPlannedLinesAfterEffectiveTermination(String status) {
        Long id = jdbcTemplate.queryForObject("""
                SELECT c.contract_id FROM fgc.insurance_contract c
                WHERE NOT EXISTS (SELECT 1 FROM fgc.contract_status_event e WHERE e.contract_id=c.contract_id
                    AND e.new_status IN ('TERMINATED','LAPSED'))
                  AND NOT EXISTS (SELECT 1 FROM fgc.schedule_header sh WHERE sh.contract_id=c.contract_id
                    AND sh.active_yn=true AND sh.schedule_purpose='OPERATIONAL' AND sh.payment_stage='GA_TO_FC')
                ORDER BY c.contract_id LIMIT 1
                """, Long.class);
        Long header = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id,payment_stage,policy_version_id,schedule_version_no,
                    schedule_purpose,scenario_code,schedule_regime)
                VALUES (?, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version),
                    1,'OPERATIONAL',NULL,'CURRENT') RETURNING schedule_header_id
                """, Long.class, id);
        for (int day = 0; day < 3; day++) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.schedule_line(schedule_header_id,line_no,installment_no,contract_month_no,
                        due_date,commission_item_id,basis_code,basis_amount,calculation_type,fixed_amount,expected_amount)
                    VALUES (?, ?, ?, ?, ?, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        'TEST', 100, 'FIXED', 100, ?)
                    """, header, day+1, day+1, day+1, LocalDate.of(2030,8,30).plusDays(day), 100*(1<<day));
        }
        jdbcTemplate.update("""
                INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,received_at,
                    source_system,source_event_key,data_origin)
                SELECT ?, COALESCE(MAX(event_seq),0)+1, ?, TIMESTAMPTZ '2030-08-30 15:30+00', now(),
                    'TEST-481', ?, 'MANUAL' FROM fgc.contract_status_event WHERE contract_id=?
                """, id, status, "TEST-481-"+status, id);
        // Seoul effective day is 8/31. Before that day the future event does not remove planned amounts.
        assertThat(arbitrageCheckRepository.sumPlannedCommissionAmount(id, PaymentStage.GA_TO_FC,
                LocalDate.of(2030,8,30))).isEqualByComparingTo("700");
        assertThat(arbitrageCheckRepository.sumPlannedCommissionAmount(id, PaymentStage.GA_TO_FC,
                LocalDate.of(2030,9,2))).isEqualByComparingTo("300");
    }

    @org.junit.jupiter.api.Test
    void revivalRestartsFuturePlannedLinesWithoutBackfillingLapsedPeriod() {
        Long id = plannedContract();
        statusEvent(id, "LAPSED", "2030-08-30T15:30:00Z");
        statusEvent(id, "REVIVED", "2030-09-01T15:30:00Z");
        statusEvent(id, "LAPSED", "2030-09-03T15:30:00Z");
        statusEvent(id, "TERMINATED", "2030-09-04T15:30:00Z");
        statusEvent(id, "REVIVED", "2030-09-09T15:30:00Z");
        assertPlanned(id, LocalDate.of(2030, 8, 30), "700");
        assertPlanned(id, LocalDate.of(2030, 9, 1), "200");
        assertPlanned(id, LocalDate.of(2030, 9, 2), "600");
        assertPlanned(id, LocalDate.of(2030, 9, 3), "600");
        assertPlanned(id, LocalDate.of(2030, 9, 6), "500");
    }

    @org.junit.jupiter.api.Test
    void sameEffectiveTimeUsesEventSequenceForRevival() {
        Long id = plannedContract();
        statusEvent(id, "LAPSED", "2030-08-30T15:30:00Z");
        statusEvent(id, "REVIVED", "2030-08-30T15:30:00Z");
        assertPlanned(id, LocalDate.of(2030, 9, 1), "700");
    }

    private Long plannedContract() {
        Long id = jdbcTemplate.queryForObject("""
                SELECT c.contract_id FROM fgc.insurance_contract c
                WHERE NOT EXISTS (SELECT 1 FROM fgc.contract_status_event e WHERE e.contract_id=c.contract_id
                    AND e.new_status IN ('TERMINATED', 'LAPSED', 'MATURED', 'CANCELLED'))
                  AND NOT EXISTS (SELECT 1 FROM fgc.schedule_header sh WHERE sh.contract_id=c.contract_id
                    AND sh.active_yn=true AND sh.schedule_purpose='OPERATIONAL' AND sh.payment_stage='GA_TO_FC')
                ORDER BY c.contract_id LIMIT 1
                """, Long.class);
        Long header = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id,payment_stage,policy_version_id,schedule_version_no,
                    schedule_purpose,scenario_code,schedule_regime)
                VALUES (?, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version),
                    1,'OPERATIONAL',NULL,'CURRENT') RETURNING schedule_header_id
                """, Long.class, id);
        for (int day = 0; day < 7; day++) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.schedule_line(schedule_header_id,line_no,installment_no,contract_month_no,
                        due_date,commission_item_id,basis_code,basis_amount,calculation_type,fixed_amount,expected_amount)
                    VALUES (?, ?, ?, ?, ?, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        'TEST', 100, 'FIXED', 100, 100)
                    """, header, day+1, day+1, day+1, LocalDate.of(2030,8,30).plusDays(day));
        }
        return id;
    }

    private void statusEvent(Long id, String status, String instant) {
        jdbcTemplate.update("""
                INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,
                    received_at,source_system,source_event_key,data_origin)
                SELECT ?, COALESCE(MAX(event_seq),0)+1, ?, ?::timestamptz, now(),
                    'TEST-481-REVIVAL', ?, 'MANUAL' FROM fgc.contract_status_event WHERE contract_id=?
                """, id, status, instant, status + instant, id);
    }

    private void assertPlanned(Long id, LocalDate asOf, String expected) {
        assertThat(arbitrageCheckRepository.sumPlannedCommissionAmount(id, PaymentStage.GA_TO_FC, asOf))
                .isEqualByComparingTo(expected);
    }

}
