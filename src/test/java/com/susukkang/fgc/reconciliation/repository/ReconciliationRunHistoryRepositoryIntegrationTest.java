package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FGC-FUN-051 / IF-API-39 — 대사 실행 이력 Repository. vw_reconciliation_summary 조인이
 * 결과 0건 실행도 빠뜨리지 않는지, 정산월·지급단계 필터가 맞는지, 같은 정산월·지급단계라도
 * 보험회사·월 검증 실행이 다르면 별도 행으로 나오는지(재실행 이력) 확인한다.
 */
@SpringBootTest
@Transactional
class ReconciliationRunHistoryRepositoryIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2098, 6, 1);

    @Autowired
    private ReconciliationRunHistoryRepository mapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long insertInsurer(String code) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type)
                VALUES (?, ?, 'LIFE')
                RETURNING insurer_id
                """, Long.class, code, code + "생명");
    }

    /** guard_run_lifecycle이 INSERT 시 CREATED만 허용한다 — 다른 상태는 UPDATE로 전이시킨다. */
    private Long insertReconciliationRun(String paymentStage, Long insurerId, Long validationRunId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, insurer_id, validation_run_id)
                VALUES (?, ?, ?, ?)
                RETURNING reconciliation_run_id
                """, Long.class, TEST_MONTH, paymentStage, insurerId, validationRunId);
    }

    private void insertReconciliationResult(Long reconRunId, String matchGroupKey, String resultType) {
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result (reconciliation_run_id, match_group_key, result_type)
                VALUES (?, ?, ?)
                """, reconRunId, matchGroupKey, resultType);
    }

    @Autowired
    private ReconciliationResultRepository resultRepository;

    @Test
    void sortsResultsByEachAllowedColumnInBothDirectionsAcrossPages() {
        Long runId = insertReconciliationRun("GA_TO_FC", insertInsurer("FUN604-R"), null);
        List<Long> contracts = jdbcTemplate.queryForList(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 3", Long.class);
        List<Long> items = jdbcTemplate.queryForList(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 3", Long.class);
        List<Long> agents = jdbcTemplate.queryForList("SELECT agent_id FROM fgc.agent ORDER BY agent_id LIMIT 3", Long.class);
        for (int i = 0; i < 9; i++) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.reconciliation_result (reconciliation_run_id, match_group_key, result_type,
                        contract_id, commission_item_id, installment_no, expected_agent_id, actual_agent_id,
                        expected_total_amount, actual_total_amount, difference_amount)
                    VALUES (?, ?, 'AMOUNT_DIFFERENCE', ?, ?, ?, ?, ?, ?, ?, ?)
                    """, runId, "FUN604-RES-" + i, contracts.get(i % contracts.size()), items.get(i % items.size()),
                    1 + i % 4, agents.get(i % agents.size()), i % 3 == 0 ? null : agents.get((i + 1) % agents.size()),
                    1000 + (i % 5) * 100, 900 + (i % 3) * 100, i % 4 * 10 - 5);
        }
        java.util.Map<String, String> columns = java.util.Map.ofEntries(
                java.util.Map.entry("createdAt", "result.created_at"),
                java.util.Map.entry("contractNo", "contract.contract_no"),
                java.util.Map.entry("commissionItemName", "item.item_name"),
                java.util.Map.entry("installmentNo", "result.installment_no"),
                java.util.Map.entry("expectedAgentName", "expected_agent.agent_name"),
                java.util.Map.entry("actualAgentName", "actual_agent.agent_name"),
                java.util.Map.entry("expectedTotalAmount", "result.expected_total_amount"),
                java.util.Map.entry("actualTotalAmount", "result.actual_total_amount"),
                java.util.Map.entry("differenceAmount", "result.difference_amount"));
        assertThat(ReconciliationResultRepository.RESULT_SORT_KEYS).containsExactlyInAnyOrderElementsOf(columns.keySet());
        for (var column : columns.entrySet()) {
            for (boolean ascending : new boolean[] {true, false}) {
                String direction = ascending ? "ASC" : "DESC";
                List<Long> expected = jdbcTemplate.queryForList("""
                        SELECT result.reconciliation_result_id
                          FROM fgc.reconciliation_result result
                          LEFT JOIN fgc.insurance_contract contract ON contract.contract_id = result.contract_id
                          LEFT JOIN fgc.commission_item item ON item.commission_item_id = result.commission_item_id
                          LEFT JOIN fgc.agent expected_agent ON expected_agent.agent_id = result.expected_agent_id
                          LEFT JOIN fgc.agent actual_agent ON actual_agent.agent_id = result.actual_agent_id
                         WHERE result.reconciliation_run_id = ?
                         ORDER BY %s %s NULLS LAST, result.reconciliation_result_id %s
                        """.formatted(column.getValue(), direction, direction), Long.class, runId);
                var sort = new com.susukkang.fgc.common.web.ListSort(column.getKey(), ascending);
                assertThat(resultRepository.findResults(runId, null, sort, 0, 100))
                        .extracting(com.susukkang.fgc.reconciliation.dto.ReconciliationResultListRow::getReconciliationResultId)
                        .as("%s %s", column.getKey(), direction).containsExactlyElementsOf(expected);
                assertThat(resultRepository.findResults(runId, null, sort, 3, 3))
                        .extracting(com.susukkang.fgc.reconciliation.dto.ReconciliationResultListRow::getReconciliationResultId)
                        .containsExactlyElementsOf(expected.stream().skip(3).limit(3).toList());
            }
        }
    }

    @Test
    void sortsByEachAllowedColumnInBothDirectionsAcrossPages() {
        // 실행마다 보험회사·대상 건수·불일치 건수를 달리하고, 같은 값(동률)과 비어 있는 일치율(0건)도 섞는다.
        String[] insurerNames = {"FUN604-C", "FUN604-A", "FUN604-B", "FUN604-A", "FUN604-C", "FUN604-B", "FUN604-D"};
        for (int i = 0; i < insurerNames.length; i++) {
            Long insurerId = insertInsurer(insurerNames[i] + i);
            Long runId = insertReconciliationRun("GA_TO_FC", insurerId, null);
            for (int r = 0; r < i % 4; r++) {
                insertReconciliationResult(runId, "FUN604-" + i + "-" + r, r == 0 ? "MATCHED" : "AMOUNT_DIFFERENCE");
            }
        }
        java.util.Map<String, String> columns = java.util.Map.ofEntries(
                java.util.Map.entry("createdAt", "rr.created_at"),
                java.util.Map.entry("reconciliationRunId", "rr.reconciliation_run_id"),
                java.util.Map.entry("settlementMonth", "rr.settlement_month"),
                java.util.Map.entry("insurerName", "ins.insurer_name"),
                java.util.Map.entry("targetCount", "vw.result_count"),
                java.util.Map.entry("matchedCount", "vw.matched_count"),
                java.util.Map.entry("exceptionCount", "vw.exception_count"),
                java.util.Map.entry("matchRatePct", "(vw.matched_count::numeric / NULLIF(vw.result_count, 0))"),
                java.util.Map.entry("differenceTotal", "vw.difference_total"),
                java.util.Map.entry("startedAt", "rr.started_at"));
        assertThat(ReconciliationRunHistoryRepository.SORT_KEYS).containsExactlyInAnyOrderElementsOf(columns.keySet());
        for (var column : columns.entrySet()) {
            for (boolean ascending : new boolean[] {true, false}) {
                String direction = ascending ? "ASC" : "DESC";
                List<Long> expected = jdbcTemplate.queryForList("""
                        SELECT rr.reconciliation_run_id
                          FROM fgc.reconciliation_run rr
                          JOIN fgc.vw_reconciliation_summary vw ON vw.reconciliation_run_id = rr.reconciliation_run_id
                          LEFT JOIN fgc.insurer ins ON ins.insurer_id = rr.insurer_id
                         WHERE rr.settlement_month = ?
                         ORDER BY %s %s NULLS LAST, rr.reconciliation_run_id %s
                        """.formatted(column.getValue(), direction, direction), Long.class, TEST_MONTH);
                var sort = new com.susukkang.fgc.common.web.ListSort(column.getKey(), ascending);
                assertThat(mapper.search(TEST_MONTH, null, null, sort, 0, 100))
                        .extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                        .as("%s %s", column.getKey(), direction).containsExactlyElementsOf(expected);
                assertThat(mapper.search(TEST_MONTH, null, null, sort, 3, 3))
                        .extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                        .containsExactlyElementsOf(expected.stream().skip(3).limit(3).toList());
            }
        }
    }

    @Test
    void searchIncludesRunsWithNoResultsAsZeroCounts() {
        Long insurerId = insertInsurer("FUN051-1");
        Long runId = insertReconciliationRun("GA_TO_FC", insurerId, null);

        List<ReconciliationRunHistoryRow> rows = mapper.search(TEST_MONTH, "GA_TO_FC", null, "desc", 0, 100);

        assertThat(rows).filteredOn(row -> row.getReconciliationRunId().equals(runId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getTargetCount()).isZero();
                    assertThat(row.getMatchedCount()).isZero();
                    assertThat(row.getExceptionCount()).isZero();
                    assertThat(row.getInsurerName()).isEqualTo("FUN051-1생명");
                });
    }

    @Test
    void searchAggregatesMatchExceptionCountsFromReconciliationResult() {
        Long insurerId = insertInsurer("FUN051-2");
        Long runId = insertReconciliationRun("GA_TO_FC", insurerId, null);
        insertReconciliationResult(runId, "FUN051-A", "MATCHED");
        insertReconciliationResult(runId, "FUN051-B", "MATCHED");
        insertReconciliationResult(runId, "FUN051-C", "AMOUNT_DIFFERENCE");

        List<ReconciliationRunHistoryRow> rows = mapper.search(TEST_MONTH, "GA_TO_FC", null, "desc", 0, 100);

        assertThat(rows).filteredOn(row -> row.getReconciliationRunId().equals(runId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getTargetCount()).isEqualTo(3);
                    assertThat(row.getMatchedCount()).isEqualTo(2);
                    assertThat(row.getExceptionCount()).isEqualTo(1);
                });
    }

    @Test
    void searchFiltersByPaymentStage() {
        Long insurerId = insertInsurer("FUN051-3");
        Long gaToFcId = insertReconciliationRun("GA_TO_FC", insurerId, null);
        Long insurerToGaId = insertReconciliationRun("INSURER_TO_GA", insurerId, null);

        List<ReconciliationRunHistoryRow> gaToFcRows = mapper.search(TEST_MONTH, "GA_TO_FC", null, "desc", 0, 100);
        List<ReconciliationRunHistoryRow> insurerToGaRows = mapper.search(TEST_MONTH, "INSURER_TO_GA", null, "desc", 0, 100);

        assertThat(gaToFcRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(gaToFcId).doesNotContain(insurerToGaId);
        assertThat(insurerToGaRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(insurerToGaId).doesNotContain(gaToFcId);
    }

    // #312 — RECO-W01 "실행 이력" 보험회사 조회 조건이 화면 select에 name이 없어 서버까지
    // 전달되지 않던 문제(criteria/mapper 배선 누락) 회귀 테스트.
    @Test
    void searchFiltersByInsurer() {
        Long insurerA = insertInsurer("FUN312-A");
        Long insurerB = insertInsurer("FUN312-B");
        Long runA = insertReconciliationRun("GA_TO_FC", insurerA, null);
        Long runB = insertReconciliationRun("GA_TO_FC", insurerB, null);

        List<ReconciliationRunHistoryRow> insurerARows = mapper.search(TEST_MONTH, "GA_TO_FC", insurerA, "desc", 0, 100);
        List<ReconciliationRunHistoryRow> insurerBRows = mapper.search(TEST_MONTH, "GA_TO_FC", insurerB, "desc", 0, 100);
        long insurerACount = mapper.count(TEST_MONTH, "GA_TO_FC", insurerA);

        assertThat(insurerARows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(runA).doesNotContain(runB);
        assertThat(insurerBRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(runB).doesNotContain(runA);
        assertThat(insurerACount).isEqualTo(1);
    }

    @Test
    void searchTreatsDifferentInsurersAsSeparateHistoryEvenWithSameMonthAndStage() {
        // 이슈 요구사항 — "동일 정산월·지급단계라도 월 검증 실행 또는 보험회사 조건이
        // 다르면 별도 실행 이력으로 조회한다" — uq_reconciliation_run이 보장하는 동작을
        // Mapper 레벨에서도 그대로 확인한다.
        Long insurerA = insertInsurer("FUN051-4A");
        Long insurerB = insertInsurer("FUN051-4B");
        Long runA = insertReconciliationRun("GA_TO_FC", insurerA, null);
        Long runB = insertReconciliationRun("GA_TO_FC", insurerB, null);

        List<ReconciliationRunHistoryRow> rows = mapper.search(TEST_MONTH, "GA_TO_FC", null, "desc", 0, 100);

        assertThat(rows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(runA, runB);
    }

    @Test
    void searchWithNoFiltersReturnsAllMonthsAndStages() {
        Long insurerId = insertInsurer("FUN051-5");
        Long runId = insertReconciliationRun("GA_TO_FC", insurerId, null);

        List<ReconciliationRunHistoryRow> rows = mapper.search(null, null, null, "desc", 0, 100);

        assertThat(rows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId).contains(runId);
    }

    @Test
    void searchOrdersByCreatedAtWithDirectionAndAppliesLimitOffset() {
        // 같은 정산월·지급단계·보험회사·월 검증 실행 조합은 uq_reconciliation_run
        // UNIQUE 제약에 걸리므로(V1:1444-1445) 보험회사를 다르게 해서 두 행을 만든다.
        // TEST_MONTH+GA_TO_FC 조합은 이 테스트 메서드 안에서 이 두 건뿐이다(클래스가
        // @Transactional이라 메서드마다 별도 트랜잭션으로 롤백되므로 다른 테스트의
        // 데이터가 섞이지 않는다) — 그래서 순서/페이지를 정확한 값으로 검증할 수 있다.
        Long insurerA = insertInsurer("FUN051-6A");
        Long insurerB = insertInsurer("FUN051-6B");
        Long runOlder = insertReconciliationRun("GA_TO_FC", insurerA, null);
        Long runNewer = insertReconciliationRun("GA_TO_FC", insurerB, null);

        List<ReconciliationRunHistoryRow> descRows = mapper.search(TEST_MONTH, "GA_TO_FC", null, "desc", 0, 100);
        List<ReconciliationRunHistoryRow> ascRows = mapper.search(TEST_MONTH, "GA_TO_FC", null, "asc", 0, 100);

        assertThat(descRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .containsExactly(runNewer, runOlder);
        assertThat(ascRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .containsExactly(runOlder, runNewer);

        // offset=0,limit=1은 desc 정렬의 첫 번째 행(runNewer)만, offset=1,limit=1은
        // 두 번째 행(runOlder)만 돌려줘야 한다 — 둘 다 확인해야 LIMIT/OFFSET이 실제로
        // 적용되는지 검증된다.
        List<ReconciliationRunHistoryRow> firstPage = mapper.search(TEST_MONTH, "GA_TO_FC", null, "desc", 0, 1);
        List<ReconciliationRunHistoryRow> secondPage = mapper.search(TEST_MONTH, "GA_TO_FC", null, "desc", 1, 1);
        assertThat(firstPage).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .containsExactly(runNewer);
        assertThat(secondPage).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .containsExactly(runOlder);
    }

    @Test
    void countMatchesSearchFilters() {
        // TEST_MONTH+GA_TO_FC 조합은 이 테스트 메서드 안에서 1건, 전체(지급단계 무관)는
        // 2건뿐이다(위 테스트와 같은 이유로 메서드 간 데이터가 섞이지 않는다) — 그래서
        // ">="가 아니라 정확한 기대값으로 검증한다. ">="였다면 필터가 전혀 안 걸려도
        // (예: WHERE 절이 통째로 빠져도) 통과했을 것이다.
        Long insurerId = insertInsurer("FUN051-7");
        insertReconciliationRun("GA_TO_FC", insurerId, null);
        insertReconciliationRun("INSURER_TO_GA", insurerId, null);

        long gaToFcCount = mapper.count(TEST_MONTH, "GA_TO_FC", null);
        long allCount = mapper.count(TEST_MONTH, null, null);

        assertThat(gaToFcCount).isEqualTo(1);
        assertThat(allCount).isEqualTo(2);
    }
}
