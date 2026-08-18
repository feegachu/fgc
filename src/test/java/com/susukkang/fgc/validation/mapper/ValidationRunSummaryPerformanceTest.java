package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.AgentCapMonitoringRow;
import com.susukkang.fgc.validation.dto.ValidationRunResultSummaryRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FGC-FUN-043 결과 집계 성능 검증 — {@link ValidationRunDetailMapper#summarize}와
 * {@link ValidationRunDetailMapper#summarizeCapByAgent}가 "시연 규모" 데이터에서도
 * N+1 없이 빠른지 확인한다.
 *
 * <p><b>데이터 규모</b>: 검증 실행 20개 × 계약 25개 = cap_check/arbitrage_check/
 * journal_header/reconciliation_result/schedule_header 각 500행(총 2,500행 + 원장
 * 라인 1,000행). 20개 실행에 나눠 심는 이유는 실제 운영에서 여러 달치 실행이 쌓인
 * 뒤 "이번 실행 것만" 빠르게 뽑아야 하는 상황을 재현하기 위해서다 — 한 실행에
 * 몰아넣으면 그 표의 거의 모든 행이 대상이 되어(선택도가 나빠져) 인덱스를 쓸 이유가
 * 없어지고, 이는 VRUN-W02가 실제로 마주치는 조회 패턴과 다르다.
 *
 * <p><b>측정 결과(로컬 개발 DB, Postgres 16, 실제 실행 1회 — 환경마다 달라질 수
 * 있어 참고용이며, 아래 테스트는 하드코딩된 임계값 대신 넉넉한 상한만 assert한다)</b>:
 * summarize() 33ms, summarizeCapByAgent() 3ms(대상 실행 1건당 25행 기준). EXPLAIN을
 * 실제로 떠 보면 cap_check는 uq_cap_check_monthly, journal_header/schedule_header는
 * 이 이슈에서 새로 추가한 인덱스(V21, V22)로 Index Scan을 쓴다(Index Cond:
 * validation_run_id = N 확인됨). reconciliation_run만 새 인덱스(idx_reconciliation_run_
 * validation_run)를 추가했는데도 Seq Scan을 쓰는데, 이 표 자체가 실행당 1행이라 전체가
 * 20행뿐이라(계약 단위가 아니라 실행 단위 테이블) 플래너가 통계상 Seq Scan을 더 싸다고
 * 정확히 판단한 것이다 — 인덱스가 안 먹힌 게 아니라 테이블이 이 규모에서 인덱스가
 * 필요 없을 만큼 작다는 뜻이라 정상이다. 이런 이유로 이 테스트는 "반드시 Index Scan"을
 * 강제로 assert하지 않고 EXPLAIN 텍스트를 stdout에 남겨 리뷰어가 직접 확인하게 한다.
 */
@SpringBootTest
@Transactional
class ValidationRunSummaryPerformanceTest {

    private static final int RUN_COUNT = 20;
    private static final int CONTRACTS_PER_RUN = 25;

    @Autowired
    private ValidationRunDetailMapper mapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long referenceInsurerId;
    private Long referenceProductOfferingId;
    private Long referenceAgentId;
    private Long referenceOrganizationId;
    private Long capRuleSetInsurerToGa;
    private Long policyVersionId;

    private void loadReferenceIds() {
        var row = jdbcTemplate.queryForMap("""
                SELECT insurer_id, product_offering_id, agent_id, organization_id
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """);
        referenceInsurerId = ((Number) row.get("insurer_id")).longValue();
        referenceProductOfferingId = ((Number) row.get("product_offering_id")).longValue();
        referenceAgentId = ((Number) row.get("agent_id")).longValue();
        referenceOrganizationId = ((Number) row.get("organization_id")).longValue();
        capRuleSetInsurerToGa = jdbcTemplate.queryForObject(
                "SELECT MIN(cap_rule_set_id) FROM fgc.cap_rule_set WHERE payment_stage = 'INSURER_TO_GA'", Long.class);
        policyVersionId = jdbcTemplate.queryForObject(
                "SELECT MIN(policy_version_id) FROM fgc.policy_version WHERE policy_type = 'CURRENT_COMMISSION'",
                Long.class);
    }

    private Long insertSyntheticContract(int seq) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract
                    (insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                     premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                     payment_term_months)
                VALUES (?, ?, ?, '2026-07-01', ?, ?, 100000, 100000, 100000, 240)
                RETURNING contract_id
                """, Long.class,
                referenceInsurerId, referenceProductOfferingId, "PERF-TEST-" + seq,
                referenceAgentId, referenceOrganizationId);
    }

    // uq_validation_run_active_month은 같은 달에 CREATED/RUNNING(=활성) MONTHLY 실행이
    // 동시에 1건만 있어야 한다는 업무 규칙(FUN-041)이다 — 여기서는 실행 20개를 전부
    // RUNNING 상태로 유지해야 하니 달을 하나씩 다르게 준다.
    private Long insertValidationRun(int monthOffset) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, 1, 'MONTHLY', 'CREATED')
                RETURNING validation_run_id
                """, Long.class, LocalDate.of(2032, 1, 1).plusMonths(monthOffset));
        jdbcTemplate.update("UPDATE fgc.validation_run SET status = 'RUNNING' WHERE validation_run_id = ?", id);
        return id;
    }

    private Long journalAccountId(String accountCode) {
        return jdbcTemplate.queryForObject(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?",
                Long.class, accountCode);
    }

    private void seedRunData(Long runId, List<Long> contractIds, int versionNo) {
        for (Long contractId : contractIds) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.cap_check
                        (validation_run_id, contract_id, payment_stage, cap_rule_set_id, check_kind, as_of_date,
                         base_premium_amount, refund_12m_amount, compliance_deduction_amount,
                         limit_amount, included_amount, remaining_amount, usage_pct, result_status, checked_at)
                    VALUES (?, ?, 'INSURER_TO_GA', ?, 'MONTHLY', '2032-01-01', 100000, 0, 0, 1200000, 0, 1200000, 0, 'NORMAL', now())
                    """, runId, contractId, capRuleSetInsurerToGa);

            jdbcTemplate.update("""
                    INSERT INTO fgc.arbitrage_check
                        (validation_run_id, contract_id, as_of_date, contract_month_no,
                         cumulative_paid_premium, net_difference_amount, standard_deduction_80_yn, result_status)
                    VALUES (?, ?, '2032-01-01', 1, 100000, 0, false, 'CLEAR')
                    """, runId, contractId);

            Long headerId = jdbcTemplate.queryForObject("""
                    INSERT INTO fgc.journal_header
                        (journal_no, journal_date, journal_type, source_entity_type, source_entity_id, validation_run_id)
                    VALUES (?, '2032-01-01', 'ADJUSTMENT', 'TEST', ?, ?)
                    RETURNING journal_header_id
                    """, Long.class, "PERF-JRN-" + runId + "-" + contractId, "PERF-JRN-" + runId + "-" + contractId, runId);
            jdbcTemplate.update("""
                    INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount)
                    VALUES (?, 1, ?, 1000)
                    """, headerId, journalAccountId("EXPECTED_RECEIVABLE"));
            jdbcTemplate.update("""
                    INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, credit_amount)
                    VALUES (?, 2, ?, 1000)
                    """, headerId, journalAccountId("EXPECTED_INCOME"));

            jdbcTemplate.update("""
                    INSERT INTO fgc.schedule_header
                        (contract_id, payment_stage, policy_version_id, schedule_version_no,
                         schedule_regime, active_yn, validation_run_id)
                    VALUES (?, 'INSURER_TO_GA', ?, ?, 'CURRENT', false, ?)
                    """, contractId, policyVersionId, versionNo, runId);
        }

        Long reconRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, validation_run_id)
                VALUES ('2032-01-01', 'INSURER_TO_GA', ?)
                RETURNING reconciliation_run_id
                """, Long.class, runId);
        for (int i = 0; i < contractIds.size(); i++) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.reconciliation_result (reconciliation_run_id, match_group_key, result_type, difference_amount)
                    VALUES (?, ?, 'MATCHED', 0)
                    """, reconRunId, "PERF-REC-" + runId + "-" + i);
        }
    }

    @Test
    void summarizeAndAgentMonitoringStayFastAtDemoScale() {
        loadReferenceIds();

        List<Long> contractIds = new ArrayList<>();
        for (int i = 0; i < CONTRACTS_PER_RUN; i++) {
            contractIds.add(insertSyntheticContract(i));
        }

        Long targetRunId = null;
        for (int r = 1; r <= RUN_COUNT; r++) {
            Long runId = insertValidationRun(r);
            seedRunData(runId, contractIds, r);
            targetRunId = runId; // 마지막 실행을 조회 대상으로 삼는다
        }

        // 총 데이터 규모 확인 — 500행씩 5개 표(원장 라인 별도 1,000행)
        Long totalCapChecks = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.cap_check", Long.class);
        assertThat(totalCapChecks).isGreaterThanOrEqualTo((long) RUN_COUNT * CONTRACTS_PER_RUN);

        long summarizeStart = System.nanoTime();
        ValidationRunResultSummaryRow summary = mapper.summarize(targetRunId);
        long summarizeMillis = (System.nanoTime() - summarizeStart) / 1_000_000;

        long agentStart = System.nanoTime();
        List<AgentCapMonitoringRow> byAgent = mapper.summarizeCapByAgent(targetRunId);
        long agentMillis = (System.nanoTime() - agentStart) / 1_000_000;

        // 정확성 — 이 실행에 심은 25건만 잡혀야 한다(다른 19개 실행 것과 섞이지 않음).
        assertThat(summary.getCapCheckedCount()).isEqualTo(CONTRACTS_PER_RUN);
        assertThat(summary.getArbitrageCheckedCount()).isEqualTo(CONTRACTS_PER_RUN);
        assertThat(summary.getJournalCount()).isEqualTo(CONTRACTS_PER_RUN);
        assertThat(summary.getReconciliationResultCount()).isEqualTo(CONTRACTS_PER_RUN);
        assertThat(summary.getScheduleGeneratedCount()).isEqualTo(CONTRACTS_PER_RUN);
        assertThat(byAgent.stream().mapToLong(AgentCapMonitoringRow::getCheckedCount).sum())
                .isEqualTo(CONTRACTS_PER_RUN);

        // 성능 — 하드웨어별 편차가 크니 절대 기준이 아니라 "N+1이 아니라면 당연히
        // 이 정도는 나와야 한다"는 넉넉한 상한선이다(실측은 클래스 Javadoc 참고).
        assertThat(summarizeMillis).as("summarize() 실행 시간(ms)").isLessThan(2000);
        assertThat(agentMillis).as("summarizeCapByAgent() 실행 시간(ms)").isLessThan(2000);

        System.out.println("[FUN-043 성능] 실행 " + RUN_COUNT + "개 x 계약 " + CONTRACTS_PER_RUN
                + "개 = summarize() " + summarizeMillis + "ms, summarizeCapByAgent() " + agentMillis + "ms");
        printExplain("cap_check", "SELECT * FROM fgc.cap_check WHERE validation_run_id = " + targetRunId);
        printExplain("journal_header", "SELECT * FROM fgc.journal_header WHERE validation_run_id = " + targetRunId);
        printExplain("reconciliation_run", "SELECT * FROM fgc.reconciliation_run WHERE validation_run_id = " + targetRunId);
        printExplain("schedule_header", "SELECT * FROM fgc.schedule_header WHERE validation_run_id = " + targetRunId);
    }

    /** EXPLAIN 결과를 stdout에 남긴다 — Seq Scan/Index Scan 여부는 리뷰어가 직접 확인한다. */
    private void printExplain(String label, String sql) {
        List<String> plan = jdbcTemplate.queryForList("EXPLAIN " + sql, String.class);
        System.out.println("[FUN-043 EXPLAIN " + label + "]");
        plan.forEach(line -> System.out.println("  " + line));
    }
}
