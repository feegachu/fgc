package com.susukkang.fgc.reconciliation.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비유효 계약 지급(INVALID_CONTRACT_PAYMENT) 신호는 계약의 현재 상태가 아니라 지급예정일 시점 상태로
 * 판정한다(SCHEDULE_ELIGIBILITY STATUS_MONTH_RULE = PAY_IF_ACTIVE_ON_DUE_DATE, V2).
 * 세션 타임존을 UTC로 바꿔도 효력일 경계는 Asia/Seoul 자정이어야 한다.
 */
@SpringBootTest
@Transactional
class ReconciliationClassificationContextIntegrationTest {

    @Autowired
    private ReconciliationResultRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useUtcSession() {
        jdbcTemplate.execute("SET LOCAL TIME ZONE 'UTC'");
    }

    /** 시드 C005: 6/02 미납 → 7/02 실효. 실효 전 예정일 지급은 정상, 실효일부터는 비유효. */
    @Test
    void lapsedContractPaymentDueBeforeLapseIsNotInvalid() {
        Long c005 = contractId("FGC-FGN01-202605-0001");

        assertThat(invalid(c005, LocalDate.of(2026, 6, 25))).isFalse();
        assertThat(invalid(c005, LocalDate.of(2026, 7, 1))).isFalse();
        assertThat(invalid(c005, LocalDate.of(2026, 7, 2))).isTrue();
        assertThat(invalid(c005, LocalDate.of(2026, 8, 31))).isTrue();
    }

    /** 시드 R7: 7/15 해지. 8/25 지급은 V4 시나리오대로 비유효로 남는다. */
    @Test
    void terminatedContractPaymentDueOnOrAfterTerminationIsInvalid() {
        Long r7 = contractId("FGC-FGN01-202603-0001");

        assertThat(invalid(r7, LocalDate.of(2026, 7, 14))).isFalse();
        assertThat(invalid(r7, LocalDate.of(2026, 7, 15))).isTrue();
        assertThat(invalid(r7, LocalDate.of(2026, 8, 25))).isTrue();
    }

    /** 판단 근거가 없으면 예전처럼 현재 상태로 본다 — 신호가 사라지는 쪽으로 틀리지 않는다. */
    @Test
    void fallsBackToCurrentStatusWithoutDueDateOrStatusEvent() {
        Long r7 = contractId("FGC-FGN01-202603-0001");
        Long manualTerminated = insertContract("IT-RECON-MANUAL", "TERMINATED", "ACTIVE", "2098-01-15 00:00+09");

        assertThat(invalid(r7, null)).isTrue();
        assertThat(invalid(manualTerminated, LocalDate.of(2098, 2, 1))).isTrue();
    }

    /** 청약철회는 처음부터 무효라 철회 전 예정일 지급도 비유효다. 유지 계약은 비유효가 아니다. */
    @Test
    void cancelledIsAlwaysInvalidAndActiveIsNever() {
        Long cancelled = insertContract("IT-RECON-CANCEL", "CANCELLED", "CANCELLED", "2098-03-01 00:00+09");
        Long active = contractId("FGC-FGL01-202607-0001");

        assertThat(invalid(cancelled, LocalDate.of(2098, 2, 1))).isTrue();
        assertThat(invalid(active, LocalDate.of(2026, 8, 25))).isFalse();
    }

    /**
     * PR #515 리뷰 회귀 — 사건 LAPSED(3/01)→REVIVED(5/01) 뒤 화면에서 LAPSED로 바꾼 계약(사건 없음, FUN-026).
     * 최신 사건(REVIVED)이 현재 상태(LAPSED)와 다르면 이력을 믿지 않고 현재 상태로 판정한다.
     * "LAPSED 사건이 한 번이라도 있었는가"로 보면 부활 뒤 예정일이 옛 사건(REVIVED) 기준 정상으로 잘못 나온다.
     */
    @Test
    void manualStatusChangeAfterEventHistoryFallsBackToCurrentStatus() {
        Long contractId = insertContract("IT-RECON-RELAPSE", "LAPSED", "ACTIVE", "2098-01-15 00:00+09");
        insertEvent(contractId, 2, "ACTIVE", "LAPSED", "2098-03-01 00:00+09");
        insertEvent(contractId, 3, "LAPSED", "REVIVED", "2098-05-01 00:00+09");

        assertThat(invalid(contractId, LocalDate.of(2098, 9, 25))).isTrue();
    }

    /**
     * 같은 효력 시각의 사건은 event_seq가 큰 쪽이 그 시점 상태다. 3/01 00:00에 LAPSED(2)·REVIVED(3)가 함께
     * 들어오고 6/01 해지된 계약은, 4/01 예정일 시점에 REVIVED라 비유효가 아니다.
     */
    @Test
    void sameEffectiveTimeUsesLatestEventSeq() {
        Long contractId = insertContract("IT-RECON-TIE", "TERMINATED", "ACTIVE", "2098-01-15 00:00+09");
        insertEvent(contractId, 2, "ACTIVE", "LAPSED", "2098-03-01 00:00+09");
        insertEvent(contractId, 3, "LAPSED", "REVIVED", "2098-03-01 00:00+09");
        insertEvent(contractId, 4, "REVIVED", "TERMINATED", "2098-06-01 00:00+09");

        assertThat(invalid(contractId, LocalDate.of(2098, 4, 1))).isFalse();
        assertThat(invalid(contractId, LocalDate.of(2098, 6, 1))).isTrue();
    }

    private void insertEvent(Long contractId, int seq, String previousStatus, String newStatus, String effectiveAt) {
        jdbcTemplate.update("""
                INSERT INTO fgc.contract_status_event (
                    contract_id, event_seq, previous_status, new_status,
                    effective_at, received_at, source_system, source_event_key, data_origin
                )
                VALUES (?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 'IT', ?, 'MANUAL')
                """, contractId, seq, previousStatus, newStatus, effectiveAt, effectiveAt,
                "IT-" + contractId + "-" + seq);
    }

    private boolean invalid(Long contractId, LocalDate dueDate) {
        return repository.findClassificationContext(contractId, null, null, List.of(), dueDate)
                .isInvalidContractPayment();
    }

    private Long contractId(String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?", Long.class, contractNo);
    }

    /** currentStatus 계약을 만들고 eventStatus 상태 사건 1건을 effectiveAt에 남긴다. */
    private Long insertContract(String contractNo, String currentStatus, String eventStatus, String effectiveAt) {
        Long contractId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium, payment_term_months,
                    current_status
                )
                SELECT p.insurer_id, po.product_offering_id, ?, DATE '2098-01-15',
                       a.agent_id, a.organization_id, 100000, 100000, 100000, 120, ?
                  FROM fgc.product p
                  JOIN fgc.product_offering po ON po.product_id = p.product_id
                                              AND po.offering_version = '2026-CURRENT-A'
                  CROSS JOIN LATERAL (
                       SELECT agent_id, organization_id FROM fgc.agent ORDER BY agent_id LIMIT 1
                  ) a
                 WHERE p.standard_product_code = 'STD-LIFE-A'
                RETURNING contract_id
                """, Long.class, contractNo, currentStatus);
        jdbcTemplate.update("""
                INSERT INTO fgc.contract_status_event (
                    contract_id, event_seq, previous_status, new_status,
                    effective_at, received_at, source_system, source_event_key, data_origin
                )
                VALUES (?, 1, NULL, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 'IT', ?, 'MANUAL')
                """, contractId, eventStatus, effectiveAt, effectiveAt, contractNo);
        return contractId;
    }
}
