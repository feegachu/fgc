package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalListRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FGC-FUN-046 / IF-API-34 — 검증원장 목록 조회 Mapper. 조건 필터링과 차변/대변 합계
 * 서브쿼리가 실제 DB에서 동작하는지 확인한다. CI/로컬 환경마다 journal_header 시드
 * 데이터가 다를 수 있어(로컬 개발 중 남은 테스트 데이터 등) 기존 행에 기대지 않고,
 * 매 테스트가 @Transactional 롤백으로 자기 데이터를 직접 만들고 지운다(journal_account는
 * V16__journal_account_seed.sql이 심어 둔 정책상 최소 계정과목 8종이라 account_code로
 * 안전하게 조회 가능).
 */
@SpringBootTest
@Transactional
class JournalSearchMapperIntegrationTest {

    @Autowired
    private JournalSearchMapper journalSearchMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long anyContractId() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
    }

    private Long journalAccountId(String accountCode) {
        return jdbcTemplate.queryForObject(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?",
                Long.class, accountCode);
    }

    /** INSERT는 DRAFT만 허용된다(guard_journal_header_write, V1__baseline_v2_1_2.sql:1296-1299). */
    private Long insertHeader(Long contractId, LocalDate journalDate, String journalNo, String journalType) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id, contract_id)
                VALUES (?, ?, ?, 'TEST', ?, ?)
                RETURNING journal_header_id
                """, Long.class, journalNo, journalDate, journalType, journalNo, contractId);
    }

    private void insertLine(Long headerId, int lineNo, String accountCode, BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, ?, ?, ?, ?)
                """, headerId, lineNo, journalAccountId(accountCode), debit, credit);
    }

    /** DRAFT 헤더 + 균형 라인 2건(차변/대변 각 50,000원)을 만든다. */
    private Long insertBalancedDraftJournal(Long contractId, LocalDate journalDate, String journalNo) {
        Long headerId = insertHeader(contractId, journalDate, journalNo, "ADJUSTMENT");
        insertLine(headerId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("50000.00"), BigDecimal.ZERO);
        insertLine(headerId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("50000.00"));
        return headerId;
    }

    @Test
    void searchByContractIdReturnsHeaderWithLineTotals() {
        Long contractId = anyContractId();
        Long headerId = insertBalancedDraftJournal(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-0001");

        List<JournalListRow> rows = journalSearchMapper.search(
                null, null, null, null, contractId, null, 0, 20);

        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getJournalHeaderId()).isEqualTo(headerId);
            assertThat(row.getStatus()).isEqualTo("DRAFT");
            // 서브쿼리가 journal_line 2건(차변 50,000/대변 50,000)을 헤더 중복 없이 합산해야 한다.
            assertThat(row.getDebitTotal()).isEqualByComparingTo("50000.00");
            assertThat(row.getCreditTotal()).isEqualByComparingTo("50000.00");
        });
    }

    @Test
    void searchByStatusExcludesNonMatchingHeaders() {
        Long contractId = anyContractId();
        Long headerId = insertBalancedDraftJournal(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-0002");

        List<JournalListRow> rows = journalSearchMapper.search(
                null, null, null, null, contractId, "POSTED", 0, 20);

        assertThat(rows).noneMatch(row -> row.getJournalHeaderId().equals(headerId));
    }

    @Test
    void searchByJournalTypeExcludesNonMatchingHeaders() {
        Long contractId = anyContractId();
        Long adjustmentId = insertHeader(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-TYPE-0001", "ADJUSTMENT");
        Long clawbackId = insertHeader(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-TYPE-0002", "CLAWBACK");

        List<JournalListRow> rows = journalSearchMapper.search(
                null, null, "ADJUSTMENT", null, contractId, null, 0, 20);

        assertThat(rows).extracting(JournalListRow::getJournalHeaderId)
                .contains(adjustmentId)
                .doesNotContain(clawbackId);
    }

    @Test
    void searchByAccountCodeReturnsHeaderOnceEvenWithMultipleMatchingLines() {
        // EXISTS 서브쿼리로 필터링하는지 확인하는 테스트다 — 만약 구현이 JOIN이었다면
        // 같은 계정(EXPECTED_RECEIVABLE)을 쓰는 라인이 2건이라 헤더가 2번 중복돼 나왔을 것이다.
        Long contractId = anyContractId();
        Long headerId = insertHeader(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-ACCT-0001", "ADJUSTMENT");
        insertLine(headerId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("30000.00"), BigDecimal.ZERO);
        insertLine(headerId, 2, "EXPECTED_RECEIVABLE", new BigDecimal("20000.00"), BigDecimal.ZERO);
        insertLine(headerId, 3, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("50000.00"));

        List<JournalListRow> matching = journalSearchMapper.search(
                null, null, null, "EXPECTED_RECEIVABLE", contractId, null, 0, 20);
        List<JournalListRow> nonMatching = journalSearchMapper.search(
                null, null, null, "ACTUAL_RECEIVABLE", contractId, null, 0, 20);

        assertThat(matching).filteredOn(row -> row.getJournalHeaderId().equals(headerId)).hasSize(1);
        assertThat(nonMatching).extracting(JournalListRow::getJournalHeaderId).doesNotContain(headerId);
    }

    @Test
    void fromAndToFilterByJournalDateRangeInclusively() {
        Long contractId = anyContractId();
        Long headerId = insertBalancedDraftJournal(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-0003");

        List<JournalListRow> inRange = journalSearchMapper.search(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), null, null, contractId, null, 0, 20);
        List<JournalListRow> outOfRange = journalSearchMapper.search(
                LocalDate.of(2026, 8, 2), null, null, null, contractId, null, 0, 20);

        assertThat(inRange).extracting(JournalListRow::getJournalHeaderId).contains(headerId);
        assertThat(outOfRange).extracting(JournalListRow::getJournalHeaderId).doesNotContain(headerId);
    }

    @Test
    void countReflectsInsertedRowRegardlessOfPageSize() {
        // count는 search의 페이지 크기(size)와 무관하게 조건에 맞는 전체 건수여야 한다.
        // rows.size()와 직접 비교하면(예: size=20) 우연히 그 계약에 기존 분개가 20건을
        // 넘는 환경에서 페이지가 잘려도 통과해 버려 count 자체의 정확성을 증명하지 못한다.
        // 그래서 삽입 전후의 count 증가량(델타)만 비교한다.
        Long contractId = anyContractId();
        long before = journalSearchMapper.count(null, null, null, null, contractId, null);

        insertBalancedDraftJournal(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-0004");

        long after = journalSearchMapper.count(null, null, null, null, contractId, null);
        assertThat(after).isEqualTo(before + 1);
    }
}
