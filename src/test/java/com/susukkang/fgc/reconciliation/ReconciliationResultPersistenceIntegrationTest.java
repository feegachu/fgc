package com.susukkang.fgc.reconciliation;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.GaFcMatchCandidate;
import com.susukkang.fgc.reconciliation.dto.InsurerGaMatchCandidate;
import com.susukkang.fgc.reconciliation.dto.ReconciliationCandidate;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchSource;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.GaFcReconciliationMatcher;
import com.susukkang.fgc.reconciliation.service.InsurerGaReconciliationMatcher;
import com.susukkang.fgc.reconciliation.service.ReconciliationExecutionCoordinator;
import com.susukkang.fgc.reconciliation.service.ReconciliationExecutionService;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

/**
 * 설명 : FUN-048-04 양방향 대사 결과 저장·실행상태 PostgreSQL 통합 테스트
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@SpringBootTest
class ReconciliationResultPersistenceIntegrationTest {

    private static final LocalDate INSURER_GA_MONTH = LocalDate.of(2097, 1, 1);
    private static final LocalDate GA_FC_MONTH = LocalDate.of(2097, 2, 1);
    private static final LocalDate IDEMPOTENT_MONTH = LocalDate.of(2097, 3, 1);
    private static final LocalDate FAILED_MONTH = LocalDate.of(2097, 4, 1);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReconciliationExecutionService executionService;

    @Autowired
    private ReconciliationExecutionCoordinator executionCoordinator;

    @Autowired
    private ReconciliationResultPersistenceService persistenceService;

    @MockitoBean
    private InsurerGaReconciliationMatcher insurerGaMatcher;

    @MockitoBean
    private GaFcReconciliationMatcher gaFcMatcher;

    private final List<Long> runIds = new ArrayList<>();
    private final List<Long> transactionIds = new ArrayList<>();
    private final List<Long> scheduleHeaderIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long runId : runIds) {
            jdbcTemplate.update("""
                    DELETE FROM fgc.reconciliation_match
                     WHERE reconciliation_result_id IN (
                         SELECT reconciliation_result_id
                           FROM fgc.reconciliation_result
                          WHERE reconciliation_run_id = ?
                     )
                    """, runId);
            jdbcTemplate.update(
                    "DELETE FROM fgc.reconciliation_result WHERE reconciliation_run_id = ?", runId);
            jdbcTemplate.update(
                    "DELETE FROM fgc.reconciliation_run WHERE reconciliation_run_id = ?", runId);
        }
        for (Long transactionId : transactionIds) {
            jdbcTemplate.update(
                    "DELETE FROM fgc.transaction_attribution WHERE commission_transaction_id = ?", transactionId);
            jdbcTemplate.update(
                    "DELETE FROM fgc.commission_transaction WHERE commission_transaction_id = ?", transactionId);
        }
        for (Long scheduleHeaderId : scheduleHeaderIds) {
            jdbcTemplate.update(
                    "DELETE FROM fgc.schedule_line WHERE schedule_header_id = ?", scheduleHeaderId);
            jdbcTemplate.update(
                    "DELETE FROM fgc.schedule_header WHERE schedule_header_id = ?", scheduleHeaderId);
        }
        reset(insurerGaMatcher, gaFcMatcher);
    }

    @Test
    void FGC_FUN_048_04_양방향_Golden_결과와_비교값을_저장하고_COMPLETED로_종결한다() {
        SourceSeed source = sourceSeed();
        Long insurerGaRunId = insertRunningRun(INSURER_GA_MONTH, PaymentStage.INSURER_TO_GA, source.insurerId());
        Long gaFcRunId = insertRunningRun(GA_FC_MONTH, PaymentStage.GA_TO_FC, source.insurerId());
        ReconciliationExecutionRequest insurerGaRequest = request(
                insurerGaRunId, INSURER_GA_MONTH, PaymentStage.INSURER_TO_GA, source.insurerId());
        ReconciliationExecutionRequest gaFcRequest = request(
                gaFcRunId, GA_FC_MONTH, PaymentStage.GA_TO_FC, source.insurerId());

        given(insurerGaMatcher.match(insurerGaRequest)).willReturn(List.of(insurerGaCandidate(source)));
        given(gaFcMatcher.match(gaFcRequest)).willReturn(List.of(gaFcCandidate(source)));

        assertThat(executionService.execute(insurerGaRequest)).isEqualTo(1);
        assertThat(executionService.execute(gaFcRequest)).isEqualTo(1);

        assertCompletedGolden(insurerGaRunId, "INSURER_TO_GA", "INSURER-CODE-01", source);
        assertCompletedGolden(gaFcRunId, "GA_TO_FC", null, source);
    }

    @Test
    void 동일_실행과_매칭키를_재저장해도_기존_snapshot과_상세행을_덮어쓰지_않는다() {
        SourceSeed source = sourceSeed();
        Long runId = insertRunningRun(IDEMPOTENT_MONTH, PaymentStage.GA_TO_FC, source.insurerId());
        ReconciliationExecutionRequest request = request(
                runId, IDEMPOTENT_MONTH, PaymentStage.GA_TO_FC, source.insurerId());
        ReconciliationCandidate original = gaFcCandidate(source);

        persistenceService.persist(request, List.of(original));
        persistenceService.persist(request, List.of(original));

        Integer resultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.reconciliation_result WHERE reconciliation_run_id = ?",
                Integer.class,
                runId);
        Integer matchCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.reconciliation_match match
                  JOIN fgc.reconciliation_result result
                    ON result.reconciliation_result_id = match.reconciliation_result_id
                 WHERE result.reconciliation_run_id = ?
                """, Integer.class, runId);

        assertThat(resultCount).isEqualTo(1);
        assertThat(matchCount).isEqualTo(2);
    }

    @Test
    void 치명적_매칭오류면_부분결과를_롤백하고_FAILED를_별도_기록한다() {
        SourceSeed source = sourceSeed();
        Long runId = insertRunningRun(FAILED_MONTH, PaymentStage.GA_TO_FC, source.insurerId());
        ReconciliationExecutionRequest request = request(
                runId, FAILED_MONTH, PaymentStage.GA_TO_FC, source.insurerId());
        given(gaFcMatcher.match(request)).willThrow(new IllegalStateException("Golden 매칭 실패"));

        assertThatThrownBy(() -> executionCoordinator.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Golden 매칭 실패");

        assertThat(status(runId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT completed_at IS NOT NULL
                  FROM fgc.reconciliation_run
                 WHERE reconciliation_run_id = ?
                """, Boolean.class, runId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.reconciliation_result WHERE reconciliation_run_id = ?",
                Integer.class,
                runId)).isZero();
    }

    private void assertCompletedGolden(
            Long runId,
            String expectedPaymentStage,
            String expectedSourceAgentCode,
            SourceSeed source
    ) {
        StoredResult result = jdbcTemplate.queryForObject("""
                SELECT run.status,
                       result.result_type,
                       result.expected_total_amount,
                       result.actual_total_amount,
                       result.difference_amount,
                       result.actual_source_agent_code,
                       result.detail_snapshot ->> 'paymentStage' AS snapshot_payment_stage,
                       jsonb_array_length(result.detail_snapshot -> 'sources') AS source_count
                  FROM fgc.reconciliation_run run
                  JOIN fgc.reconciliation_result result
                    ON result.reconciliation_run_id = run.reconciliation_run_id
                 WHERE run.reconciliation_run_id = ?
                """, (resultSet, rowNum) -> new StoredResult(
                resultSet.getString("status"),
                resultSet.getString("result_type"),
                resultSet.getBigDecimal("expected_total_amount"),
                resultSet.getBigDecimal("actual_total_amount"),
                resultSet.getBigDecimal("difference_amount"),
                resultSet.getString("actual_source_agent_code"),
                resultSet.getString("snapshot_payment_stage"),
                resultSet.getInt("source_count")
        ), runId);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.resultType()).isEqualTo("MATCHED");
        assertThat(result.expectedAmount()).isEqualByComparingTo("1000");
        assertThat(result.actualAmount()).isEqualByComparingTo("1000");
        assertThat(result.differenceAmount()).isZero();
        assertThat(result.actualSourceAgentCode()).isEqualTo(expectedSourceAgentCode);
        assertThat(result.snapshotPaymentStage()).isEqualTo(expectedPaymentStage);
        assertThat(result.sourceCount()).isEqualTo(2);

        List<StoredMatch> matches = jdbcTemplate.query("""
                SELECT match_seq, schedule_line_id, transaction_attribution_id, matched_amount, match_role
                  FROM fgc.reconciliation_match match
                  JOIN fgc.reconciliation_result result
                    ON result.reconciliation_result_id = match.reconciliation_result_id
                 WHERE result.reconciliation_run_id = ?
                 ORDER BY match_seq
                """, (resultSet, rowNum) -> new StoredMatch(
                resultSet.getInt("match_seq"),
                resultSet.getObject("schedule_line_id", Long.class),
                resultSet.getObject("transaction_attribution_id", Long.class),
                resultSet.getBigDecimal("matched_amount"),
                resultSet.getString("match_role")
        ), runId);
        assertThat(matches).containsExactly(
                new StoredMatch(1, source.scheduleLineId(), null, new BigDecimal("1000.00"), "EXPECTED"),
                new StoredMatch(2, null, source.attributionId(), new BigDecimal("1000.00"), "ACTUAL")
        );
    }

    private Long insertRunningRun(LocalDate month, PaymentStage paymentStage, Long insurerId) {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'", Long.class);
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (
                    settlement_month, payment_stage, insurer_id, created_by
                ) VALUES (?, ?, ?, ?)
                RETURNING reconciliation_run_id
                """, Long.class, month, paymentStage.name(), insurerId, userId);
        jdbcTemplate.update("""
                UPDATE fgc.reconciliation_run
                   SET status = 'RUNNING', started_at = clock_timestamp()
                 WHERE reconciliation_run_id = ?
                """, runId);
        runIds.add(runId);
        return runId;
    }

    private SourceSeed sourceSeed() {
        SourceMaster master = jdbcTemplate.queryForObject("""
                SELECT contract.contract_id,
                       contract.insurer_id,
                       contract.agent_id,
                       policy.policy_version_id,
                       item.commission_item_id
                  FROM fgc.insurance_contract contract
                  CROSS JOIN LATERAL (
                      SELECT policy_version_id
                        FROM fgc.policy_version
                       ORDER BY policy_version_id
                       LIMIT 1
                  ) policy
                  CROSS JOIN LATERAL (
                      SELECT commission_item_id
                        FROM fgc.commission_item
                       WHERE cashflow_type = 'PAYMENT'
                       ORDER BY commission_item_id
                       LIMIT 1
                  ) item
                 WHERE contract.agent_id IS NOT NULL
                 ORDER BY contract.contract_id
                 LIMIT 1
                """, (resultSet, rowNum) -> new SourceMaster(
                resultSet.getLong("contract_id"),
                resultSet.getLong("insurer_id"),
                resultSet.getLong("agent_id"),
                resultSet.getLong("policy_version_id"),
                resultSet.getLong("commission_item_id")
        ));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime, active_yn
                ) VALUES (?, 'GA_TO_FC', ?, 9999, 'COMPARISON', ?, 'CURRENT', true)
                RETURNING schedule_header_id
                """, Long.class, master.contractId(), master.policyVersionId(), "IT-048-04-" + suffix);
        scheduleHeaderIds.add(scheduleHeaderId);
        Long scheduleLineId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount,
                    calculation_type, fixed_amount, expected_amount
                ) VALUES (?, 1, 1, 1, ?, ?, ?, 'IT_RECONCILIATION', 1000, 'FIXED', 1000, 1000)
                RETURNING schedule_line_id
                """, Long.class,
                scheduleHeaderId, INSURER_GA_MONTH.plusDays(14),
                master.commissionItemId(), master.agentId());

        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, policy_version_id, installment_no,
                    settlement_month, due_date, amount, cashflow_type, status
                ) VALUES ('GA_TO_FC', 'GA_MANUAL_PAYMENT', ?, ?, ?, ?, ?, 1, ?, ?, 1000, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                "IT-048-04:" + suffix, master.contractId(), master.agentId(),
                master.commissionItemId(), master.policyVersionId(),
                INSURER_GA_MONTH, INSURER_GA_MONTH.plusDays(14));
        transactionIds.add(transactionId);
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    agent_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, 1000, 'INCLUDED', 'DIRECT')
                RETURNING transaction_attribution_id
                """, Long.class,
                transactionId, master.contractId(), master.agentId(),
                INSURER_GA_MONTH.plusDays(14), INSURER_GA_MONTH);
        return new SourceSeed(
                scheduleLineId,
                attributionId,
                master.contractId(),
                master.insurerId(),
                master.agentId(),
                master.commissionItemId()
        );
    }

    private static ReconciliationExecutionRequest request(
            Long runId,
            LocalDate month,
            PaymentStage paymentStage,
            Long insurerId
    ) {
        return new ReconciliationExecutionRequest(runId, null, month, paymentStage, insurerId, null);
    }

    private static InsurerGaMatchCandidate insurerGaCandidate(SourceSeed source) {
        return new InsurerGaMatchCandidate(
                "GOLDEN:INSURER_TO_GA",
                source.contractId(),
                source.agentId(),
                source.agentId(),
                "INSURER-CODE-01",
                source.commissionItemId(),
                1,
                1,
                INSURER_GA_MONTH.plusDays(14),
                INSURER_GA_MONTH,
                ReconciliationResultType.MATCHED,
                new BigDecimal("1000"),
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                "MATCHED",
                List.of(),
                List.of(source.scheduleLineId()),
                List.of(source.attributionId()),
                List.of(11L),
                List.of(12L),
                sourceMatches(source)
        );
    }

    private static GaFcMatchCandidate gaFcCandidate(SourceSeed source) {
        return new GaFcMatchCandidate(
                "GOLDEN:GA_TO_FC",
                source.contractId(),
                source.agentId(),
                source.agentId(),
                source.commissionItemId(),
                1,
                1,
                GA_FC_MONTH.plusDays(14),
                GA_FC_MONTH,
                ReconciliationResultType.MATCHED,
                new BigDecimal("1000"),
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                "MATCHED",
                List.of(),
                List.of(source.scheduleLineId()),
                List.of(source.attributionId()),
                List.of(21L),
                List.of(22L),
                sourceMatches(source)
        );
    }

    private static List<ReconciliationMatchSource> sourceMatches(SourceSeed source) {
        return List.of(
                new ReconciliationMatchSource(
                        source.scheduleLineId(), null, 11L, new BigDecimal("1000"), "EXPECTED"),
                new ReconciliationMatchSource(
                        null, source.attributionId(), 12L, new BigDecimal("1000"), "ACTUAL")
        );
    }

    private String status(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.reconciliation_run WHERE reconciliation_run_id = ?",
                String.class,
                runId);
    }

    private record SourceSeed(
            Long scheduleLineId,
            Long attributionId,
            Long contractId,
            Long insurerId,
            Long agentId,
            Long commissionItemId
    ) {
    }

    private record SourceMaster(
            Long contractId,
            Long insurerId,
            Long agentId,
            Long policyVersionId,
            Long commissionItemId
    ) {
    }

    private record StoredResult(
            String status,
            String resultType,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            BigDecimal differenceAmount,
            String actualSourceAgentCode,
            String snapshotPaymentStage,
            int sourceCount
    ) {
    }

    private record StoredMatch(
            int matchSeq,
            Long scheduleLineId,
            Long transactionAttributionId,
            BigDecimal matchedAmount,
            String matchRole
    ) {
    }
}
