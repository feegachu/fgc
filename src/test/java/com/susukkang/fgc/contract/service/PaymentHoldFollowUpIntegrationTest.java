package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEffectResponse;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * FUN-028 / CT-C02: 실제 PostgreSQL 에서 미확정·확정 혼합 fixture 의 보류 범위, 재처리 수렴, 확정 차단, 영향 조회를 확인한다.
 * worker 의 잠금·재시도는 ContractStatusEventIntegrationTest 가 확인하므로 여기서는 처리기와 확정 게이트를 직접 부른다.
 * 시험 트랜잭션은 롤백되어 공유 DB 에 계약·지급을 남기지 않는다.
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "SETTLEMENT") // 지급 확정은 SETTLEMENT 가 소유한다(@PreAuthorize CAN_PROCESS)
class PaymentHoldFollowUpIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentHoldFollowUpProcessor processor;
    @Autowired private ContractStatusEventRepository events;
    @Autowired private CommissionPaymentService payments;
    @Autowired private ContractStatusEffectQueryService effectQuery;
    @Autowired private PaymentHoldGate gate;

    @Test
    void lapseHoldsOnlyUnconfirmedTargetsAndKeepsConfirmedOnesUntouched() {
        Fixture f = fixture();

        List<ContractStatusEffect> recorded = processor.process(events.findById(f.lapse).orElseThrow());

        assertThat(recorded).extracting(ContractStatusEffect::entityType, ContractStatusEffect::entityId,
                        ContractStatusEffect::nextState)
                .contains(tuple(ContractStatusEffect.EntityType.SCHEDULE_LINE, f.plannedLine, "CANCELLED"),
                        tuple(ContractStatusEffect.EntityType.PAYMENT, f.draftPayment, "HELD"),
                        tuple(ContractStatusEffect.EntityType.PAYMENT, f.confirmedPayment, "EXCLUDED"),
                        tuple(ContractStatusEffect.EntityType.SCHEDULE_LINE, f.confirmedLine, "EXCLUDED"));
        assertThat(lineStatus(f.plannedLine)).isEqualTo("CANCELLED");
        assertThat(lineStatus(f.confirmedLine)).isEqualTo("CONFIRMED");
        assertThat(openHolds(f.draftPayment)).isEqualTo(1);
        assertThat(openHolds(f.confirmedPayment)).isZero();
        // 보존 대상: 확정 지급의 상태·금액은 그대로다. 보류는 지급 상태를 바꾸지 않는다.
        assertThat(jdbc.queryForObject("SELECT status || ':' || amount FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                String.class, f.confirmedPayment)).isEqualTo("CONFIRMED:5000.00");
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                String.class, f.draftPayment)).isEqualTo("DRAFT");
    }

    @Test
    void unpaidHoldsTheLineAsHoldNotCancelledAndKeepsPaymentHoldSeparate() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long unpaid = statusEvent(contract, 2, "UNPAID", "2026-09-01T00:00:00+09:00");
        long line = line(header(contract), 1, "2026-09-25", "PLANNED");
        long payment = payment(contract, "2026-09-20", false);

        processor.process(events.findById(unpaid).orElseThrow());

        assertThat(lineStatus(line)).isEqualTo("HOLD");
        assertThat(openHolds(payment)).isEqualTo(1);
    }

    @Test
    void reprocessingTheSameEventConvergesToTheSameResult() {
        Fixture f = fixture();
        var event = events.findById(f.lapse).orElseThrow();

        processor.process(event);
        long holds = count("SELECT count(*) FROM fgc.payment_hold");
        String lines = jdbc.queryForObject(
                "SELECT string_agg(line_status, ',' ORDER BY schedule_line_id) FROM fgc.schedule_line WHERE schedule_line_id IN (?, ?)",
                String.class, f.plannedLine, f.confirmedLine);
        processor.process(event);

        assertThat(count("SELECT count(*) FROM fgc.payment_hold")).isEqualTo(holds);
        assertThat(jdbc.queryForObject(
                "SELECT string_agg(line_status, ',' ORDER BY schedule_line_id) FROM fgc.schedule_line WHERE schedule_line_id IN (?, ?)",
                String.class, f.plannedLine, f.confirmedLine)).isEqualTo(lines);
    }

    @Test
    void supportsOnlyStatusesThatHoldOrStopPayments() {
        long contract = contract("2026-03-01");
        for (String status : List.of("UNPAID", "LAPSED", "TERMINATED", "MATURED", "CANCELLED")) {
            assertThat(processor.supports(events.findById(statusEvent(contract, seq(contract), status,
                    "2026-09-01T00:00:00+09:00")).orElseThrow())).as(status).isTrue();
        }
        for (String status : List.of("ACTIVE", "REVIVED", "APPLIED")) {
            assertThat(processor.supports(events.findById(statusEvent(contract, seq(contract), status,
                    "2026-09-01T00:00:00+09:00")).orElseThrow())).as(status).isFalse();
        }
    }

    @Test
    void confirmingAHeldPaymentIsRejectedAndLeavesItDraft() {
        Fixture f = fixture();
        processor.process(events.findById(f.lapse).orElseThrow());

        assertThatThrownBy(() -> payments.confirm(f.draftPayment, "hold-key-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_PAYMENT_HELD));
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                String.class, f.draftPayment)).isEqualTo("DRAFT");
    }

    @Test
    void confirmingIsRejectedWhileAStatusEventJobIsStillPending() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long lapse = statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        long payment = payment(contract, "2026-09-20", false);
        // worker 가 아직 처리하지 않은 상태: 대기 작업만 있고 보류 행은 없다.
        jdbc.update("INSERT INTO fgc.contract_event_job (contract_status_event_id, processing_job) VALUES (?, ?)",
                lapse, PaymentHoldFollowUpProcessor.JOB);

        assertThatThrownBy(() -> payments.confirm(payment, "pending-key-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_PAYMENT_HELD));
    }

    @Test
    void resumingFromUnpaidReleasesTheHoldsAndResetsTheDueDateToTheResumeDate() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long unpaid = statusEvent(contract, 2, "UNPAID", "2026-09-01T00:00:00+09:00");
        long line = line(header(contract), 1, "2026-09-25", "PLANNED");
        long payment = payment(contract, "2026-09-20", false);
        processor.process(events.findById(unpaid).orElseThrow());
        assertThat(lineStatus(line)).isEqualTo("HOLD");
        assertThat(openHolds(payment)).isEqualTo(1);

        long resumed = resumeEvent(contract, 3, "2026-10-05T00:00:00+09:00");
        var event = events.findById(resumed).orElseThrow();
        assertThat(processor.supports(event)).isTrue();
        List<ContractStatusEffect> recorded = processor.process(event);

        assertThat(recorded).extracting(ContractStatusEffect::entityType, ContractStatusEffect::entityId,
                        ContractStatusEffect::nextState)
                .containsExactlyInAnyOrder(tuple(ContractStatusEffect.EntityType.SCHEDULE_LINE, line, "PLANNED"),
                        tuple(ContractStatusEffect.EntityType.PAYMENT, payment, "RELEASED"));
        assertThat(lineStatus(line)).isEqualTo("PLANNED");
        assertThat(jdbc.queryForObject("SELECT due_date::text FROM fgc.schedule_line WHERE schedule_line_id = ?", String.class, line))
                .isEqualTo("2026-10-05");
        assertThat(openHolds(payment)).isZero();
        assertThat(gate.blockers(payment)).isEmpty();
        // 다시 처리해도 같은 결과로 수렴한다.
        assertThat(processor.process(event)).isEmpty();
    }

    @Test
    void aRevivalIsNotTheProcessorsBusiness() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        long revived = statusEvent(contract, 3, "REVIVED", "2026-10-15T00:00:00+09:00");

        assertThat(processor.supports(events.findById(revived).orElseThrow())).isFalse();
    }

    @Test
    void aDraftCreatedAfterTheLapseWasProcessedIsStillBlockedByTheCurrentStatus() {
        Fixture f = fixture();
        processor.process(events.findById(f.lapse).orElseThrow());
        // 처리가 끝난 뒤 실효 기간 귀속일로 새로 만든 초안에는 보류 행이 없지만, 현재 상태 이력으로 판단하면 보류 대상이다.
        long late = payment(f.contract, "2026-09-28", false);
        assertThat(openHolds(late)).isZero();

        assertThatThrownBy(() -> payments.confirm(late, "late-key-1"))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_PAYMENT_HELD));
    }

    @Test
    void aContractWithoutHoldingEventsIsNotJudgedByTheGate() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long payment = payment(contract, "2026-09-20", false);

        assertThat(gate.blockers(payment)).isEmpty();
    }

    @Test
    void aNewScheduleVersionIsHeldAgainFromTheLatestStatusEvent() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long unpaid = statusEvent(contract, 2, "UNPAID", "2026-09-01T00:00:00+09:00");
        long header = header(contract);
        long confirmed = line(header, 1, "2026-08-10", "CONFIRMED");
        // 새 버전이 HOLD·CANCELLED 회차를 PLANNED 로 다시 만든 상황
        long rebuilt = line(header, 2, "2026-09-25", "PLANNED");

        processor.reapplyLatest(contract);

        assertThat(lineStatus(rebuilt)).isEqualTo("HOLD");
        assertThat(lineStatus(confirmed)).isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM fgc.contract_status_event_effect WHERE contract_status_event_id = " + unpaid
                + " AND entity_id = " + rebuilt)).isEqualTo(1);
        // 다시 불러도 이미 맞춰진 회차는 바뀌지 않는다.
        processor.reapplyLatest(contract);
        assertThat(count("SELECT count(*) FROM fgc.contract_status_event_effect WHERE entity_id = " + rebuilt)).isEqualTo(1);
    }

    @Test
    void aContractWithoutHoldingEventsIsLeftAloneByReapply() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long line = line(header(contract), 1, "2026-09-25", "PLANNED");

        processor.reapplyLatest(contract);

        assertThat(lineStatus(line)).isEqualTo("PLANNED");
    }

    @Test
    void aReleasedHoldNoLongerBlocksTheOpenHoldCheck() {
        Fixture f = fixture();
        processor.process(events.findById(f.lapse).orElseThrow());
        long revival = statusEvent(f.contract, 3, "REVIVED", "2026-10-15T00:00:00+09:00");

        jdbc.update("UPDATE fgc.payment_hold SET released_at = clock_timestamp(), released_by_event_id = ? "
                + "WHERE commission_transaction_id = ?", revival, f.draftPayment);

        assertThat(openHolds(f.draftPayment)).isZero();
        assertThat(count("SELECT count(*) FROM fgc.payment_hold WHERE commission_transaction_id = " + f.draftPayment)).isEqualTo(1);
    }

    @Test
    void aHoldCannotBeReleasedTwice() {
        Fixture f = fixture();
        processor.process(events.findById(f.lapse).orElseThrow());
        long revival = statusEvent(f.contract, 3, "REVIVED", "2026-10-15T00:00:00+09:00");
        String release = "UPDATE fgc.payment_hold SET released_at = clock_timestamp(), released_by_event_id = " + revival
                + " WHERE commission_transaction_id = " + f.draftPayment;
        jdbc.update(release);

        assertThatThrownBy(() -> jdbc.update(release)).hasMessageContaining("single release");
    }

    @Test
    void aHoldCannotBeDeleted() {
        Fixture f = fixture();
        processor.process(events.findById(f.lapse).orElseThrow());

        assertThatThrownBy(() -> jdbc.update("DELETE FROM fgc.payment_hold WHERE commission_transaction_id = ?", f.draftPayment))
                .hasMessageContaining("append-only");
    }

    @Test
    void effectQueryReturnsRecordedEffectsOfTheContractsOwnEventOnly() {
        Fixture f = fixture();
        jdbc.update("""
                INSERT INTO fgc.contract_status_event_effect(contract_status_event_id, processing_job, entity_type, entity_id,
                    previous_state, next_state, amount, reason_code, reason)
                VALUES (?, ?, 'PAYMENT', ?, 'DRAFT', 'HELD', 5000, 'STATUS_INACTIVE', '보류'),
                       (?, ?, 'PAYMENT', ?, 'CONFIRMED', 'EXCLUDED', NULL, 'ALREADY_CONFIRMED', '확정 건 제외')
                """, f.lapse, PaymentHoldFollowUpProcessor.JOB, f.draftPayment,
                f.lapse, PaymentHoldFollowUpProcessor.JOB, f.confirmedPayment);

        PageResponse<ContractStatusEffectResponse> page = effectQuery.effects(f.contract, f.lapse, 1, 10);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(ContractStatusEffectResponse::nextState, ContractStatusEffectResponse::amount)
                .containsExactlyInAnyOrder(tuple("HELD", new BigDecimal("5000.00")), tuple("EXCLUDED", null));
        long other = contract("2026-03-02");
        assertThatThrownBy(() -> effectQuery.effects(other, f.lapse, 1, 10))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
        assertThatThrownBy(() -> effectQuery.effects(f.contract, f.lapse, 1, 101))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
    }

    private record Fixture(long contract, long lapse, long plannedLine, long confirmedLine, long draftPayment,
                           long confirmedPayment) {
    }

    /** 활성 계약이 2026-09-01 에 실효: 9월 회차·지급은 중단·보류 대상, 8월 확정 회차·확정 지급은 보존 대상이다. */
    private Fixture fixture() {
        long contract = contract("2026-03-01");
        statusEvent(contract, 1, "ACTIVE", "2026-03-01T00:00:00+09:00");
        long lapse = statusEvent(contract, 2, "LAPSED", "2026-09-01T00:00:00+09:00");
        long header = header(contract);
        long confirmedLine = line(header, 1, "2026-09-10", "CONFIRMED");
        long plannedLine = line(header, 2, "2026-09-25", "PLANNED");
        long draftPayment = payment(contract, "2026-09-20", false);
        long confirmedPayment = payment(contract, "2026-09-21", true);
        return new Fixture(contract, lapse, plannedLine, confirmedLine, draftPayment, confirmedPayment);
    }

    /** 미납(UNPAID)에서 정상(ACTIVE)으로 돌아온 사건. */
    private long resumeEvent(long contractId, int eventSeq, String effectiveAt) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, previous_status, new_status, effective_at,
                    received_at, source_system, source_event_key, data_origin)
                VALUES (?, ?, 'UNPAID', 'ACTIVE', ?::timestamptz, ?::timestamptz, 'TEST-523', ?, 'MANUAL')
                RETURNING contract_status_event_id
                """, Long.class, contractId, eventSeq, effectiveAt, effectiveAt, UUID.randomUUID().toString());
    }

    private int seq(long contract) {
        return jdbc.queryForObject("SELECT COALESCE(max(event_seq), 0) + 1 FROM fgc.contract_status_event WHERE contract_id = ?",
                Integer.class, contract);
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
