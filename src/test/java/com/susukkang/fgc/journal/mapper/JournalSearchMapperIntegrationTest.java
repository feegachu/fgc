package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalListRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #127 검증원장 목록 조회 Mapper — 조건 필터링과 차변/대변 합계 서브쿼리가 실제 DB에서
 * 동작하는지 확인한다. CI/로컬 환경마다 journal_header 시드 데이터가 다를 수 있어(로컬
 * 개발 중 남은 테스트 데이터 등) 기존 행에 기대지 않고, 매 테스트가 @Transactional
 * 롤백으로 자기 데이터를 직접 만들고 지운다(journal_account는 정책 시드 8종이라
 * account_code로 안전하게 조회 가능 — V1__baseline_v2_1_2.sql 제39조 최소 계정과목).
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

    /** DRAFT 헤더 + 균형 라인 2건(차변/대변 각 50,000원)을 만든다 — INSERT는 DRAFT만 허용된다
     * (guard_journal_header_write, V1__baseline_v2_1_2.sql:1296-1299). */
    private Long insertBalancedDraftJournal(Long contractId, LocalDate journalDate, String journalNo) {
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id, contract_id)
                VALUES (?, ?, 'ADJUSTMENT', 'TEST', ?, ?)
                RETURNING journal_header_id
                """, Long.class, journalNo, journalDate, journalNo, contractId);

        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, 1, ?, 50000.00, 0)
                """, headerId, journalAccountId("EXPECTED_RECEIVABLE"));
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, 2, ?, 0, 50000.00)
                """, headerId, journalAccountId("EXPECTED_INCOME"));

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
    void countMatchesSearchResultSize() {
        Long contractId = anyContractId();
        insertBalancedDraftJournal(contractId, LocalDate.of(2026, 8, 1), "TEST-SEARCH-0004");

        List<JournalListRow> rows = journalSearchMapper.search(
                null, null, null, null, contractId, null, 0, 20);
        long total = journalSearchMapper.count(null, null, null, null, contractId, null);

        assertThat(total).isEqualTo(rows.size());
    }
}
