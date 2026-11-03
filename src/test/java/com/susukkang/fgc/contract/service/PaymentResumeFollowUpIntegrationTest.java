package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * FUN-029 / CT-C02: 실제 PostgreSQL 에서 실효 → 부활의 재개 대상·비대상 구분, 과거 소급 금지, 지급 건 자동 확정 금지,
 * 확정 보존, 재처리 수렴을 확인한다. 시험 트랜잭션은 롤백되어 공유 DB 에 계약·지급을 남기지 않는다.
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "SETTLEMENT")
class PaymentResumeFollowUpIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentHoldFollowUpProcessor holdProcessor;
    @Autowired private PaymentResumeFollowUpProcessor resumeProcessor;
    @Autowired private ContractStatusEventRepository events;
    @Autowired private PaymentHoldGate gate;
    @Autowired private CommissionPaymentService payments;

    private record Scenario(long contract, long lapse, long revival, long confirmedLine, long lapsePeriodLine,
                            long afterRevivalLine, long laterLine, long lapsePeriodPayment, long afterRevivalPayment,
                            long confirmedPayment) {
    }

    /** ACTIVE(3/1) → 실효(9/1, 028 처리) → 부활(10/15). 9월 회차·지급은 실효 기간, 10/25 이후는 부활 뒤다. */
    private Scenario lapsedThenRevived() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long lapse = statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        long header = header(contract);
        long confirmedLine = line(header, 1, "2026-08-10", "CONFIRMED");
        long lapsePeriodLine = line(header, 2, "2026-09-25", "PLANNED");
        long afterRevivalLine = line(header, 3, "2026-10-25", "PLANNED");
        long laterLine = line(header, 4, "2026-11-25", "PLANNED");
        long lapsePeriodPayment = payment(contract, "2026-09-20", false);
        long afterRevivalPayment = payment(contract, "2026-10-20", false);
        long confirmedPayment = payment(contract, "2026-09-21", true);
        holdProcessor.process(events.findById(lapse).orElseThrow());
        long revival = statusEvent(contract, 3, "REVIVED", "2026-10-15T00:00:00+09:00");
        return new Scenario(contract, lapse, revival, confirmedLine, lapsePeriodLine, afterRevivalLine, laterLine,
                lapsePeriodPayment, afterRevivalPayment, confirmedPayment);
    }

    @Test
    void onlyTargetsAfterTheRevivalDateAreResumedAndTheLapsePeriodIsNotRestored() {
        Scenario s = lapsedThenRevived();
        assertThat(lineStatus(s.afterRevivalLine)).isEqualTo("CANCELLED");
        assertThat(openHolds(s.afterRevivalPayment)).isEqualTo(1);

        List<ContractStatusEffect> recorded = resumeProcessor.process(events.findById(s.revival).orElseThrow());

        // 재개 대상: 부활 효력일 이후 회차와 그 귀속의 지급 건
        assertThat(lineStatus(s.afterRevivalLine)).isEqualTo("PLANNED");
        assertThat(lineStatus(s.laterLine)).isEqualTo("PLANNED");
        assertThat(openHolds(s.afterRevivalPayment)).isZero();
        // 비대상: 실효 기간은 소급하지 않는다
        assertThat(lineStatus(s.lapsePeriodLine)).isEqualTo("CANCELLED");
        assertThat(openHolds(s.lapsePeriodPayment)).isEqualTo(1);
        // 근거: 대상과 비대상이 모두 effect 로 남는다
        assertThat(recorded).extracting(ContractStatusEffect::entityType, ContractStatusEffect::entityId,
                        ContractStatusEffect::nextState, ContractStatusEffect::reasonCode)
                .contains(tuple(EntityType.SCHEDULE_LINE, s.afterRevivalLine, "PLANNED", "STATUS_ACTIVE"),
                        tuple(EntityType.SCHEDULE_LINE, s.laterLine, "PLANNED", "STATUS_ACTIVE"),
                        tuple(EntityType.PAYMENT, s.afterRevivalPayment, "RELEASED", "STATUS_ACTIVE"),
                        tuple(EntityType.SCHEDULE_LINE, s.lapsePeriodLine, "EXCLUDED", "NO_RETROACTIVE"),
                        tuple(EntityType.PAYMENT, s.lapsePeriodPayment, "EXCLUDED", "NO_RETROACTIVE"));
        assertThat(recorded).extracting(ContractStatusEffect::entityId).doesNotContain(s.confirmedLine, s.confirmedPayment);
    }

    @Test
    void resumingNeverConfirmsAPaymentAndTheHeldOneStillCannotBeConfirmed() {
        Scenario s = lapsedThenRevived();
        resumeProcessor.process(events.findById(s.revival).orElseThrow());

        // 보류 해제만 하고 자동으로 확정하지 않는다.
        assertThat(paymentStatus(s.afterRevivalPayment)).isEqualTo("DRAFT");
        assertThat(gate.blockers(s.afterRevivalPayment)).isEmpty();
        // 다른(실효 기간) 보류가 남은 지급 건은 계속 확정할 수 없다.
        assertThat(gate.blockers(s.lapsePeriodPayment)).isNotEmpty();
        assertThatThrownBy(() -> payments.confirm(s.lapsePeriodPayment, "resume-key-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_PAYMENT_HELD));
        assertThat(paymentStatus(s.lapsePeriodPayment)).isEqualTo("DRAFT");
    }

    @Test
    void confirmedResultsAreKeptAsTheyWere() {
        Scenario s = lapsedThenRevived();
        resumeProcessor.process(events.findById(s.revival).orElseThrow());

        assertThat(lineStatus(s.confirmedLine)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status || ':' || amount FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                String.class, s.confirmedPayment)).isEqualTo("CONFIRMED:5000.00");
        assertThat(openHolds(s.confirmedPayment)).isZero();
    }

    @Test
    void reprocessingTheSameRevivalConvergesToTheSameResult() {
        Scenario s = lapsedThenRevived();
        var event = events.findById(s.revival).orElseThrow();
        resumeProcessor.process(event);
        long holds = count("SELECT count(*) FROM fgc.payment_hold WHERE released_at IS NOT NULL");

        List<ContractStatusEffect> again = resumeProcessor.process(event);

        assertThat(count("SELECT count(*) FROM fgc.payment_hold WHERE released_at IS NOT NULL")).isEqualTo(holds);
        assertThat(lineStatus(s.afterRevivalLine)).isEqualTo("PLANNED");
        assertThat(lineStatus(s.lapsePeriodLine)).isEqualTo("CANCELLED");
        // 되살린 대상은 다시 바뀌지 않는다(비대상 근거만 되풀이해 돌려준다).
        assertThat(again).extracting(ContractStatusEffect::nextState).containsOnly("EXCLUDED");
    }

    @Test
    void eventsRecordedAfterTheRevivalAreNotSeenByItsJudgement() {
        // 부활(10/15) 뒤 다시 미납(11/01): 같은 사건이면 언제 판단해도 같은 결과여야 하므로 그 뒤 사건은 보지 않는다.
        Scenario s = lapsedThenRevived();
        statusEvent(s.contract, 4, "UNPAID", "2026-11-01T00:00:00+09:00");

        resumeProcessor.process(events.findById(s.revival).orElseThrow());

        // 11/25 회차는 부활 시점 기준으로는 정상이라 재개된다. 이후 미납의 보류는 그 사건의 028 처리가 맡는다.
        assertThat(lineStatus(s.laterLine)).isEqualTo("PLANNED");
    }

    @Test
    void supportsOnlyTheRevivalEvent() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long lapse = statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        long revived = statusEvent(contract, 3, "REVIVED", "2026-10-15T00:00:00+09:00");
        long active = resumeEvent(contract, 4, "2026-10-20T00:00:00+09:00");

        assertThat(resumeProcessor.supports(events.findById(revived).orElseThrow())).isTrue();
        assertThat(resumeProcessor.supports(events.findById(lapse).orElseThrow())).isFalse();
        assertThat(resumeProcessor.supports(events.findById(active).orElseThrow())).isFalse();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long openHolds(long paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM fgc.payment_hold WHERE commission_transaction_id = ? AND released_at IS NULL",
                Long.class, paymentId);
    }

    private String lineStatus(long lineId) {
        return jdbc.queryForObject("SELECT line_status FROM fgc.schedule_line WHERE schedule_line_id = ?", String.class, lineId);
    }

    private String paymentStatus(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                String.class, paymentId);
    }

    /** 미납(UNPAID)에서 정상(ACTIVE)으로 돌아온 사건. */
    private long resumeEvent(long contractId, int eventSeq, String effectiveAt) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, previous_status, new_status, effective_at,
                    received_at, source_system, source_event_key, data_origin)
                VALUES (?, ?, 'UNPAID', 'ACTIVE', ?::timestamptz, ?::timestamptz, 'TEST-720', ?, 'MANUAL')
                RETURNING contract_status_event_id
                """, Long.class, contractId, eventSeq, effectiveAt, effectiveAt, UUID.randomUUID().toString());
    }

    private int seq(long contract) {
        return jdbc.queryForObject("SELECT COALESCE(max(event_seq), 0) + 1 FROM fgc.contract_status_event WHERE contract_id = ?",
                Integer.class, contract);
    }

    private long contract(String contractDate) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id, product_offering_id, contract_no, contract_date, agent_id,
                    organization_id, premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    payment_term_months, current_status, data_origin)
                SELECT insurer_id, product_offering_id, ?, ?::date, agent_id, organization_id, 100000, 100000, 100000,
                       120, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                RETURNING contract_id
                """, Long.class, "P523-" + UUID.randomUUID(), contractDate);
    }

    private long statusEvent(long contractId, int eventSeq, String status, String effectiveAt) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, new_status, effective_at, received_at,
                    source_system, source_event_key, data_origin)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, 'TEST-523', ?, 'MANUAL')
                RETURNING contract_status_event_id
                """, Long.class, contractId, eventSeq, status, effectiveAt, effectiveAt, UUID.randomUUID().toString());
    }

    private long header(long contractId) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, schedule_regime)
                VALUES (?, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version), 1, 'OPERATIONAL', 'CURRENT')
                RETURNING schedule_header_id
                """, Long.class, contractId);
    }

    private long line(long headerId, int installment, String dueDate, String status) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type, fixed_amount, expected_amount, line_status)
                VALUES (?, ?, ?, ?, ?::date, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        'TEST', 1000, 'FIXED', 1000, 1000, ?)
                RETURNING schedule_line_id
                """, Long.class, headerId, installment, installment, installment, dueDate, status);
    }

    private long payment(long contractId, String attributionDate, boolean confirmed) {
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, commission_item_id,
                    settlement_month, amount, cashflow_type)
                VALUES ('INSURER_TO_GA', 'INSURER_STATEMENT', ?, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        date_trunc('month', ?::date)::date, 5000, 'PAYMENT')
                RETURNING commission_transaction_id
                """, Long.class, "P523-" + UUID.randomUUID(), attributionDate);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, contract_id, attribution_date,
                    attribution_month, attributed_amount, inclusion_status_snapshot, attribution_method)
                VALUES (?, ?, ?::date, date_trunc('month', ?::date)::date, 5000, 'INCLUDED', 'DIRECT')
                """, id, contractId, attributionDate, attributionDate);
        if (confirmed) {
            jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", id);
        }
        return id;
    }
}
