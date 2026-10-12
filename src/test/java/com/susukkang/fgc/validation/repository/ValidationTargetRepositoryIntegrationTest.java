package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidationTargetSelectionMapperIntegrationTest(MyBatis)와 같은 시나리오를
 * ValidationTargetRepository(JPA, 네이티브 벌크 insert)로 검증한다.
 */
@SpringBootTest
@Transactional
class ValidationTargetRepositoryIntegrationTest {

    @Autowired
    private ValidationTargetRepository validationTargetRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fgcFun042InsertTargetsSelectsSeedContractsByStandardProductCode() {
        LocalDate validationMonth = LocalDate.of(2026, 7, 1);
        Long runId = insertValidationRun(validationMonth);

        int inserted = validationTargetRepository.insertTargets(runId, validationMonth, LocalDate.of(2026, 7, 31));

        assertThat(inserted).isPositive();
        List<String> selectedContractNos = jdbcTemplate.queryForList("""
                SELECT ic.contract_no
                  FROM fgc.validation_target vt
                  JOIN fgc.insurance_contract ic ON ic.contract_id = vt.contract_id
                 WHERE vt.validation_run_id = ? AND vt.selection_status = 'SELECTED'
                """, String.class, runId);
        long selected = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_status = 'SELECTED'
                """, Long.class, runId);
        long productCodeMismatch = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_reason = '상품코드 불일치'
                """, Long.class, runId);
        long selectedWithoutTable = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ? AND selection_status = 'SELECTED'
                   AND refund_rate_table_id IS NULL
                """, Long.class, runId);
        long withoutStandardCodeEvidence = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.validation_target
                 WHERE validation_run_id = ?
                   AND (snapshot ->> 'standardProductCode' IS NULL
                        OR snapshot ->> 'insurerProductCode' IS NOT NULL)
                """, Long.class, runId);

        // #474 — 7월에 살아 있던 계약은 지금 상태와 무관하게 7월 대상이다.
        // C005(FGC-FGN01-202605-0001) 7/02 실효, A1(FGC-FGL02-202601-0001) 8/20·A2(FGC-FGL01-202601-0001) 8/22 해지.
        assertThat(selectedContractNos).containsExactlyInAnyOrder(
                "FGC-FGL01-202607-0001",
                "FGC-FGL01-202607-0002",
                "FGC-FGL01-202607-0003",
                "FGC-FGL01-202607-0004",
                "FGC-FGL01-202607-0005",
                "FGC-FGL02-202605-0001",
                "FGC-FGN01-202605-0001",
                "FGC-FGL02-202601-0001",
                "FGC-FGL01-202601-0001");
        assertThat(selected).isPositive();
        assertThat(productCodeMismatch).isZero();
        assertThat(selectedWithoutTable).isZero();
        assertThat(withoutStandardCodeEvidence).isZero();
    }

    @Test
    void fgc332SavContractIsSelectedWithSeededRefundRateTable() {
        Long contractId = insertSavContract();
        Long runId = insertValidationRun(LocalDate.of(2098, 3, 1));

        validationTargetRepository.insertTargets(runId, LocalDate.of(2098, 3, 1), LocalDate.of(2098, 3, 31));

        var target = jdbcTemplate.queryForMap("""
                SELECT selection_status, selection_reason, refund_rate_table_id
                  FROM fgc.validation_target
                 WHERE validation_run_id = ? AND contract_id = ?
                """, runId, contractId);
        assertThat(target.get("selection_status")).isEqualTo("SELECTED");
        assertThat(target.get("selection_reason")).isEqualTo("월 통합검증 대상");
        assertThat(target.get("refund_rate_table_id")).isNotNull();
    }

    /**
     * #474 — 시드의 해지·실효 계약은 효력일(effective_at)이 속한 달까지 대상이고 그 다음 달부터 빠진다.
     * C005 7/02 실효, R7 7/15 해지(납입기간 120개월 환급률표가 없어 검토필요), A1 8/20·A2 8/22 해지.
     * A1·A2는 7월에 살아 있었으므로 지금 해지 상태여도 7월 재실행 대상이다(재실행 재현성).
     */
    @Test
    void fgc474SeedTerminatedAndLapsedContractsStayTargetsThroughTheirEffectiveMonth() {
        LocalDate july = LocalDate.of(2026, 7, 1);
        LocalDate august = LocalDate.of(2026, 8, 1);
        Long julyRunId = insertValidationRun(july);
        Long augustRunId = insertValidationRun(august);

        validationTargetRepository.insertTargets(julyRunId, july, LocalDate.of(2026, 7, 31));
        validationTargetRepository.insertTargets(augustRunId, august, LocalDate.of(2026, 8, 31));

        assertThat(targetStatuses(julyRunId))
                .containsEntry("FGC-FGN01-202605-0001", "SELECTED")
                .containsEntry("FGC-FGN01-202603-0001", "REVIEW_REQUIRED")
                .containsEntry("FGC-FGL02-202601-0001", "SELECTED")
                .containsEntry("FGC-FGL01-202601-0001", "SELECTED");
        assertThat(targetStatuses(augustRunId))
                .containsEntry("FGC-FGL02-202601-0001", "SELECTED")
                .containsEntry("FGC-FGL01-202601-0001", "SELECTED")
                .doesNotContainKeys("FGC-FGN01-202605-0001", "FGC-FGN01-202603-0001");
    }

    /**
     * #474 — 효력일 경계는 Asia/Seoul 자정이다. 세션 타임존을 UTC로 바꿔도
     * 3/01 00:00(KST)=2/28 15:00(UTC) 해지는 2월이 아니라 3월 사건으로 보고 3월 대상에 넣는다.
     * 2/28 23:59(KST) 해지처럼 검증월 이전 해지는 넣지 않는다. 검증월 이후(4/01·5/10) 해지는
     * 3월에 살아 있었으므로 넣는다. 살아 있는 UNPAID·REVIVED는 사건 없이도 넣는다.
     */
    @Test
    void fgc474SelectsByEffectiveDateInSeoulRegardlessOfSessionTimeZone() {
        jdbcTemplate.execute("SET LOCAL TIME ZONE 'UTC'");
        Long monthStart = insertSavContract("IT-474-START", "TERMINATED", "2098-03-01 00:00+09");
        Long monthEnd = insertSavContract("IT-474-END", "LAPSED", "2098-03-31 23:59+09");
        Long nextMonth = insertSavContract("IT-474-NEXT", "TERMINATED", "2098-04-01 00:00+09");
        Long previousMonth = insertSavContract("IT-474-PREV", "TERMINATED", "2098-02-28 23:59+09");
        Long later = insertSavContract("IT-474-LATER", "TERMINATED", "2098-05-10 00:00+09");
        Long unpaid = insertSavContract("IT-474-UNPAID", "UNPAID", null);
        Long revived = insertSavContract("IT-474-REVIVED", "REVIVED", null);
        Long runId = insertValidationRun(LocalDate.of(2098, 3, 1));

        validationTargetRepository.insertTargets(runId, LocalDate.of(2098, 3, 1), LocalDate.of(2098, 3, 31));

        List<Long> targetIds = jdbcTemplate.queryForList(
                "SELECT contract_id FROM fgc.validation_target WHERE validation_run_id = ?", Long.class, runId);
        assertThat(targetIds).contains(monthStart, monthEnd, nextMonth, later, unpaid, revived)
                .doesNotContain(previousMonth);
    }

    @Test
    void insertTargetsIsIdempotentOnRetryViaOnConflictDoNothing() {
        LocalDate validationMonth = LocalDate.of(2026, 7, 1);
        Long runId = insertValidationRun(validationMonth);
        validationTargetRepository.insertTargets(runId, validationMonth, LocalDate.of(2026, 7, 31));
        long firstCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_target WHERE validation_run_id = ?", Long.class, runId);

        int secondInsert = validationTargetRepository.insertTargets(runId, validationMonth, LocalDate.of(2026, 7, 31));

        assertThat(secondInsert).isZero();
        long secondCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_target WHERE validation_run_id = ?", Long.class, runId);
        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void selectsOnlySelectedContractsFromRequestedRunInContractOrder() {
        Long requestedRunId = insertValidationRun(LocalDate.of(2097, 1, 1));
        Long otherRunId = insertValidationRun(LocalDate.of(2097, 2, 1));
        List<Long> contractIds = jdbcTemplate.queryForList(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 3",
                Long.class);
        assertThat(contractIds).hasSize(3);

        insertTarget(requestedRunId, contractIds.get(2), "SELECTED");
        insertTarget(requestedRunId, contractIds.get(0), "SELECTED");
        insertTarget(requestedRunId, contractIds.get(1), "REVIEW_REQUIRED");
        insertTarget(otherRunId, contractIds.get(1), "SELECTED");

        List<Long> selected = validationTargetRepository.selectSelectedContractIds(requestedRunId);

        assertThat(selected).containsExactly(contractIds.get(0), contractIds.get(2));
    }

    /**
     * #380 Phase 4 — ValidationTargetRepository.selectScheduleStates(unnest + ROW() 기반
     * DISTINCT COUNT를 네이티브 쿼리로 옮긴 메서드)가 선별된 계약의 두 지급 단계를 모두
     * 반환하는지 검증한다. 구 MyBatis ValidationScheduleMapper는 이 전환 완료 후 다른
     * 패키지에서 참조가 없어 삭제되었다 — 이 테스트가 그 검증 책임을 이어받는다.
     */
    @Test
    void selectScheduleStatesReturnsBothPaymentStagesForSelectedContract() {
        LocalDate validationMonth = LocalDate.of(2098, 7, 1);
        Long validationRunId = insertValidationRun(validationMonth);
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1",
                Long.class);
        insertTarget(validationRunId, contractId, "SELECTED");

        List<ValidationScheduleState> states = validationTargetRepository.selectScheduleStates(validationRunId);

        assertThat(states).hasSize(2);
        assertThat(states).extracting(ValidationScheduleState::getPaymentStage)
                .containsExactlyInAnyOrder(PaymentStage.INSURER_TO_GA, PaymentStage.GA_TO_FC);
        assertThat(states).allSatisfy(state -> {
            assertThat(state.getContractId()).isEqualTo(contractId);
            assertThat(state.getActiveHeaderCount()).isNotNull();
            assertThat(state.getLineCount()).isNotNull();
            assertThat(state.getDistinctLineCount()).isNotNull();
        });
    }

    @Test
    void selectScheduleStatesSumsScheduleAmountsAfterRoundingEachLineToWon() {
        LocalDate validationMonth = LocalDate.of(2098, 8, 1);
        Long validationRunId = insertValidationRun(validationMonth);
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1",
                Long.class);
        LocalDate contractDate = jdbcTemplate.queryForObject(
                "SELECT contract_date FROM fgc.insurance_contract WHERE contract_id = ?",
                LocalDate.class, contractId);
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version WHERE status = 'ACTIVE' ORDER BY policy_version_id LIMIT 1",
                Long.class);
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        insertTarget(validationRunId, contractId, "SELECTED");
        jdbcTemplate.update("""
                UPDATE fgc.schedule_header
                   SET active_yn = false
                 WHERE contract_id = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND schedule_purpose = 'OPERATIONAL'
                   AND active_yn = true
                """, contractId);
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn
                )
                VALUES (?, 'GA_TO_FC', ?, 999, 'CURRENT', 'OPERATIONAL', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, policyVersionId);
        jdbcTemplate.update("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type,
                    rate_pct, expected_amount
                ) VALUES
                    (?, 1, 1, 1, ?, ?, 'TEST_AMOUNT', 10.40, 'RATE', 100.000000, 10.40),
                    (?, 2, 2, 1, ?, ?, 'TEST_AMOUNT', 10.40, 'RATE', 100.000000, 10.40)
                """, scheduleHeaderId, contractDate, commissionItemId,
                scheduleHeaderId, contractDate, commissionItemId);

        ValidationScheduleState gaToFc = validationTargetRepository.selectScheduleStates(validationRunId).stream()
                .filter(state -> state.getPaymentStage() == PaymentStage.GA_TO_FC)
                .findFirst()
                .orElseThrow();

        assertThat(gaToFc.getTotalAmount()).isEqualByComparingTo(new BigDecimal("20"));
    }

    private Long insertSavContract() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium, payment_term_months
                )
                SELECT p.insurer_id, po.product_offering_id, 'IT-332-SAV-0001', DATE '2098-02-15',
                       a.agent_id, a.organization_id, 200000, 200000, 200000, 120
                  FROM fgc.product p
                  JOIN fgc.product_offering po ON po.product_id = p.product_id
                                              AND po.offering_version = '2026-CURRENT-A'
                                              AND po.channel_code = 'FACE_TO_FACE'
                  CROSS JOIN LATERAL (
                       SELECT agent_id, organization_id FROM fgc.agent ORDER BY agent_id LIMIT 1
                  ) a
                 WHERE p.standard_product_code = 'STD-SAV-A'
                RETURNING contract_id
                """, Long.class);
    }

    /** STD-SAV-A(환급률표 있음) 계약을 status로 만들고, effectiveAt이 있으면 그 시각의 상태 사건을 남긴다. */
    private Long insertSavContract(String contractNo, String status, String effectiveAt) {
        Long contractId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium, payment_term_months,
                    current_status
                )
                SELECT p.insurer_id, po.product_offering_id, ?, DATE '2098-01-15',
                       a.agent_id, a.organization_id, 200000, 200000, 200000, 120, ?
                  FROM fgc.product p
                  JOIN fgc.product_offering po ON po.product_id = p.product_id
                                              AND po.offering_version = '2026-CURRENT-A'
                                              AND po.channel_code = 'FACE_TO_FACE'
                  CROSS JOIN LATERAL (
                       SELECT agent_id, organization_id FROM fgc.agent ORDER BY agent_id LIMIT 1
                  ) a
                 WHERE p.standard_product_code = 'STD-SAV-A'
                RETURNING contract_id
                """, Long.class, contractNo, status);
        if (effectiveAt != null) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.contract_status_event (
                        contract_id, event_seq, previous_status, new_status,
                        effective_at, received_at, source_system, source_event_key, data_origin
                    )
                    VALUES (?, 1, 'ACTIVE', ?, CAST(? AS timestamptz), CAST(? AS timestamptz),
                            'IT', ?, 'MANUAL')
                    """, contractId, status, effectiveAt, effectiveAt, contractNo);
        }
        return contractId;
    }

    private Map<String, String> targetStatuses(Long validationRunId) {
        Map<String, String> statuses = new HashMap<>();
        jdbcTemplate.query("""
                SELECT ic.contract_no, vt.selection_status
                  FROM fgc.validation_target vt
                  JOIN fgc.insurance_contract ic ON ic.contract_id = vt.contract_id
                 WHERE vt.validation_run_id = ?
                """, (RowCallbackHandler)
                rs -> statuses.put(rs.getString(1), rs.getString(2)), validationRunId);
        return statuses;
    }

    private Long insertValidationRun(LocalDate validationMonth) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (
                    validation_month, run_no, run_type, status
                )
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, validationMonth, validationMonth);
    }

    private void insertTarget(Long validationRunId, Long contractId, String status) {
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (
                    validation_run_id, contract_id, product_offering_id,
                    selection_status, selection_reason
                )
                SELECT ?, contract_id, product_offering_id, ?, 'TEST'
                  FROM fgc.insurance_contract
                 WHERE contract_id = ?
                """, validationRunId, status, contractId);
    }
}
