package com.susukkang.fgc.reconciliation.mapper;

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
 * FGC-FUN-051 / IF-API-39 — 대사 실행 이력 Mapper. vw_reconciliation_summary 조인이
 * 결과 0건 실행도 빠뜨리지 않는지, 정산월·지급단계 필터가 맞는지, 같은 정산월·지급단계라도
 * 보험회사·월 검증 실행이 다르면 별도 행으로 나오는지(재실행 이력) 확인한다.
 */
@SpringBootTest
@Transactional
class ReconciliationRunHistoryMapperIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2098, 6, 1);

    @Autowired
    private ReconciliationRunHistoryMapper mapper;
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
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, insurer_id, validation_run_id)
                VALUES (?, ?, ?, ?)
                RETURNING reconciliation_run_id
                """, Long.class, TEST_MONTH, paymentStage, insurerId, validationRunId);
        return id;
    }

    private void insertReconciliationResult(Long reconRunId, String matchGroupKey, String resultType) {
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result (reconciliation_run_id, match_group_key, result_type)
                VALUES (?, ?, ?)
                """, reconRunId, matchGroupKey, resultType);
    }

    @Test
    void searchIncludesRunsWithNoResultsAsZeroCounts() {
        Long insurerId = insertInsurer("FUN051-1");
        Long runId = insertReconciliationRun("GA_TO_FC", insurerId, null);

        List<ReconciliationRunHistoryRow> rows = mapper.search(TEST_MONTH, "GA_TO_FC", "desc", 0, 100);

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

        List<ReconciliationRunHistoryRow> rows = mapper.search(TEST_MONTH, "GA_TO_FC", "desc", 0, 100);

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

        List<ReconciliationRunHistoryRow> gaToFcRows = mapper.search(TEST_MONTH, "GA_TO_FC", "desc", 0, 100);
        List<ReconciliationRunHistoryRow> insurerToGaRows = mapper.search(TEST_MONTH, "INSURER_TO_GA", "desc", 0, 100);

        assertThat(gaToFcRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(gaToFcId).doesNotContain(insurerToGaId);
        assertThat(insurerToGaRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(insurerToGaId).doesNotContain(gaToFcId);
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

        List<ReconciliationRunHistoryRow> rows = mapper.search(TEST_MONTH, "GA_TO_FC", "desc", 0, 100);

        assertThat(rows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .contains(runA, runB);
    }

    @Test
    void searchWithNoFiltersReturnsAllMonthsAndStages() {
        Long insurerId = insertInsurer("FUN051-5");
        Long runId = insertReconciliationRun("GA_TO_FC", insurerId, null);

        List<ReconciliationRunHistoryRow> rows = mapper.search(null, null, "desc", 0, 100);

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

        List<ReconciliationRunHistoryRow> descRows = mapper.search(TEST_MONTH, "GA_TO_FC", "desc", 0, 100);
        List<ReconciliationRunHistoryRow> ascRows = mapper.search(TEST_MONTH, "GA_TO_FC", "asc", 0, 100);

        assertThat(descRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .containsExactly(runNewer, runOlder);
        assertThat(ascRows).extracting(ReconciliationRunHistoryRow::getReconciliationRunId)
                .containsExactly(runOlder, runNewer);

        // offset=0,limit=1은 desc 정렬의 첫 번째 행(runNewer)만, offset=1,limit=1은
        // 두 번째 행(runOlder)만 돌려줘야 한다 — 둘 다 확인해야 LIMIT/OFFSET이 실제로
        // 적용되는지 검증된다.
        List<ReconciliationRunHistoryRow> firstPage = mapper.search(TEST_MONTH, "GA_TO_FC", "desc", 0, 1);
        List<ReconciliationRunHistoryRow> secondPage = mapper.search(TEST_MONTH, "GA_TO_FC", "desc", 1, 1);
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

        long gaToFcCount = mapper.count(TEST_MONTH, "GA_TO_FC");
        long allCount = mapper.count(TEST_MONTH, null);

        assertThat(gaToFcCount).isEqualTo(1);
        assertThat(allCount).isEqualTo(2);
    }
}
