package com.susukkang.fgc.reconciliation;

import com.susukkang.fgc.reconciliation.service.ReconciliationResultQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** FUN-049-02/03 실제 PostgreSQL 목록·상세 Golden 테스트. */
@SpringBootTest
class ReconciliationResultQueryIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2098, 1, 1);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReconciliationResultQueryService queryService;

    private Long runId;
    private Long firstResultId;

    @BeforeEach
    void setUp() {
        cleanTestRun();
        runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage)
                VALUES (?, 'GA_TO_FC')
                RETURNING reconciliation_run_id
                """, Long.class, TEST_MONTH);
        firstResultId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_result (
                    reconciliation_run_id, match_group_key, result_type,
                    expected_total_amount, actual_total_amount, difference_amount,
                    primary_reason_code, secondary_reason_codes, detail_snapshot
                ) VALUES (?, 'FUN-049:GOLDEN:1', 'JOURNAL_IMBALANCE',
                          1000, 900, -100, 'JOURNAL_IMBALANCE',
                          ARRAY['AMOUNT_DIFFERENCE','REVIEW_REQUIRED']::text[],
                          '{"paymentStage":"GA_TO_FC","expectedJournalHeaderIds":[11]}'::jsonb)
                RETURNING reconciliation_result_id
                """, Long.class, runId);
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result (
                    reconciliation_run_id, match_group_key, result_type,
                    expected_total_amount, actual_total_amount, difference_amount,
                    primary_reason_code, secondary_reason_codes, detail_snapshot
                ) VALUES (?, 'FUN-049:GOLDEN:2', 'MATCHED',
                          2000, 2000, 0, 'MATCHED', ARRAY[]::text[], '{}'::jsonb)
                """, runId);
    }

    @AfterEach
    void tearDown() {
        cleanTestRun();
    }

    @Test
    void listFilterPagingSummaryAndDetailAreReadFromPersistedGoldenRows() {
        var page = queryService.search(runId, "JOURNAL_IMBALANCE", 1, 20, "createdAt,asc");

        assertThat(page.items().content()).hasSize(1);
        assertThat(page.items().content().getFirst().primaryReason().code())
                .isEqualTo("JOURNAL_IMBALANCE");
        assertThat(page.items().content().getFirst().secondaryReasons())
                .extracting("code")
                .containsExactly("AMOUNT_DIFFERENCE", "REVIEW_REQUIRED");
        assertThat(page.items().totalElements()).isEqualTo(1);
        assertThat(page.summary().resultCount()).isEqualTo(2);
        assertThat(page.summary().matchedCount()).isEqualTo(1);
        assertThat(page.summary().exceptionCount()).isEqualTo(1);

        var detail = queryService.get(firstResultId);
        assertThat(detail.expectedTotalAmount()).isEqualByComparingTo("1000.00");
        assertThat(detail.actualTotalAmount()).isEqualByComparingTo("900.00");
        assertThat(detail.differenceAmount()).isEqualByComparingTo("-100.00");
        assertThat(detail.matches()).isEmpty();
        assertThat(detail.detailSnapshot().get("paymentStage").asText()).isEqualTo("GA_TO_FC");
    }

    @Test
    void contractFilterKeepsPagingCountAndOtherContractsSeparate() {
        var contracts = jdbcTemplate.queryForList("SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 2", Long.class);
        assertThat(contracts).hasSize(2);
        jdbcTemplate.update("UPDATE fgc.reconciliation_result SET contract_id = ? WHERE reconciliation_result_id = ?", contracts.getFirst(), firstResultId);
        jdbcTemplate.update("UPDATE fgc.reconciliation_result SET contract_id = ? WHERE reconciliation_run_id = ? AND reconciliation_result_id <> ?", contracts.getLast(), runId, firstResultId);
        var page = queryService.search(runId, null, contracts.getFirst(), 1, 1, "createdAt,asc");
        assertThat(page.items().content()).extracting("reconciliationResultId").containsExactly(firstResultId);
        assertThat(page.items().totalElements()).isEqualTo(1);
        assertThat(queryService.search(runId, null, contracts.getFirst(), 2, 1, "createdAt,asc").items().content()).isEmpty();
        assertThat(queryService.search(runId, "MATCHED", contracts.getFirst(), 1, 20, "createdAt,asc").items().totalElements()).isZero();
        assertThat(queryService.search(runId, null, 9223372036854775807L, 1, 20, "createdAt,asc").items().content()).isEmpty();
    }

    @Test
    void basisFollowsOnlyCompletedCorrectionChainAndPreservesDetection() {
        Long second = revision(runId, true);
        Long latest = revision(second, false);
        Long unrelated = revision(null, false);
        Long corrected = revisedResult(latest, "FUN-049:GOLDEN:1", 1000);
        revisedResult(unrelated, "FUN-049:GOLDEN:1", 999999);
        var basis = queryService.basis(firstResultId);
        assertThat(basis.currentRunId()).isEqualTo(latest);
        assertThat(basis.currentResults()).extracting("reconciliationResultId").containsExactly(corrected);
        assertThat(basis.currentResults().getFirst().actualTotalAmount()).isEqualByComparingTo("1000");
        assertThat(basis.detected().actualTotalAmount()).isEqualByComparingTo("900");
        assertThat(basis.detected().resultType()).isEqualTo("JOURNAL_IMBALANCE");
        jdbcTemplate.update("UPDATE fgc.reconciliation_result SET match_group_key='FUN-049:GOLDEN:REMOVED' WHERE reconciliation_result_id=?", corrected);
        var removed = queryService.basis(firstResultId);
        assertThat(removed.currentRunId()).isEqualTo(latest);
        assertThat(removed.currentResults()).isEmpty();
    }

    private Long revision(Long prior, boolean superseded) {
        if (prior != null) jdbcTemplate.update("UPDATE fgc.reconciliation_run SET superseded_yn=true WHERE reconciliation_run_id=?", prior);
        Long insurer = prior == null ? jdbcTemplate.queryForObject("SELECT min(insurer_id) FROM fgc.insurer", Long.class)
                : jdbcTemplate.queryForObject("SELECT insurer_id FROM fgc.reconciliation_run WHERE reconciliation_run_id=?", Long.class, prior);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run(settlement_month,payment_stage,corrects_reconciliation_run_id,insurer_id)
                VALUES (?, 'GA_TO_FC', ?, ?) RETURNING reconciliation_run_id
                """, Long.class, TEST_MONTH, prior, insurer);
        jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='RUNNING', started_at=clock_timestamp() WHERE reconciliation_run_id=?", id);
        jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='COMPLETED', completed_at=clock_timestamp(), superseded_yn=? WHERE reconciliation_run_id=?", superseded, id);
        return id;
    }

    private Long revisedResult(Long run, String key, int actual) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_result(reconciliation_run_id,match_group_key,result_type,
                    expected_total_amount,actual_total_amount,difference_amount,primary_reason_code,detail_snapshot)
                VALUES (?,?,'MATCHED',1000,?,0,'MATCHED','{}'::jsonb) RETURNING reconciliation_result_id
                """, Long.class, run, key, actual);
    }

    private void cleanTestRun() {
        jdbcTemplate.update("""
                DELETE FROM fgc.reconciliation_match
                 WHERE reconciliation_result_id IN (
                     SELECT result.reconciliation_result_id
                       FROM fgc.reconciliation_result result
                       JOIN fgc.reconciliation_run run
                         ON run.reconciliation_run_id = result.reconciliation_run_id
                      WHERE run.settlement_month = ?
                        AND run.payment_stage = 'GA_TO_FC'
                        AND result.match_group_key LIKE 'FUN-049:GOLDEN:%'
                 )
                """, TEST_MONTH);
        jdbcTemplate.update("""
                DELETE FROM fgc.reconciliation_result
                 WHERE reconciliation_run_id IN (
                     SELECT reconciliation_run_id
                       FROM fgc.reconciliation_run
                      WHERE settlement_month = ? AND payment_stage = 'GA_TO_FC'
                 )
                   AND match_group_key LIKE 'FUN-049:GOLDEN:%'
                """, TEST_MONTH);
        jdbcTemplate.update("""
                DELETE FROM fgc.reconciliation_run
                 WHERE settlement_month = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND NOT EXISTS (
                       SELECT 1 FROM fgc.reconciliation_result result
                        WHERE result.reconciliation_run_id = reconciliation_run.reconciliation_run_id
                   )
                """, TEST_MONTH);
    }
}
