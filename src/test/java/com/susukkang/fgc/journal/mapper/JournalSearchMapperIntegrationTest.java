package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalListRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증원장 목록 조회 Mapper — 조건 필터링과 차변/대변 합계 서브쿼리가 실제 DB에서
 * 동작하는지만 확인한다(시드 데이터: journal_header_id=60, contract_id=7, POSTED,
 * 차변/대변 각 50,000원씩 균형 라인 2건).
 */
@SpringBootTest
class JournalSearchMapperIntegrationTest {

    @Autowired
    private JournalSearchMapper journalSearchMapper;

    @Test
    void searchByContractIdReturnsSeededHeaderWithLineTotals() {
        List<JournalListRow> rows = journalSearchMapper.search(
                null, null, null, null, 7L, null, 0, 20);

        assertThat(rows).hasSize(1);
        JournalListRow row = rows.get(0);
        assertThat(row.getJournalHeaderId()).isEqualTo(60L);
        assertThat(row.getContractNo()).isEqualTo("FGC-FGL01-202703-0001");
        assertThat(row.getStatus()).isEqualTo("POSTED");
        // 서브쿼리가 journal_line 2건(차변 50,000/대변 50,000)을 헤더 중복 없이 합산해야 한다.
        assertThat(row.getDebitTotal()).isEqualByComparingTo("50000.00");
        assertThat(row.getCreditTotal()).isEqualByComparingTo("50000.00");
    }

    @Test
    void searchByStatusExcludesNonMatchingHeaders() {
        List<JournalListRow> rows = journalSearchMapper.search(
                null, null, null, null, null, "DRAFT", 0, 20);

        assertThat(rows).noneMatch(row -> row.getJournalHeaderId().equals(60L));
    }

    @Test
    void fromAndToFilterByJournalDateRangeInclusively() {
        List<JournalListRow> inRange = journalSearchMapper.search(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), null, null, 7L, null, 0, 20);
        List<JournalListRow> outOfRange = journalSearchMapper.search(
                LocalDate.of(2026, 8, 2), null, null, null, 7L, null, 0, 20);

        assertThat(inRange).hasSize(1);
        assertThat(outOfRange).isEmpty();
    }

    @Test
    void countMatchesSearchResultSize() {
        long total = journalSearchMapper.count(null, null, null, null, 7L, null);

        assertThat(total).isEqualTo(1L);
    }
}
