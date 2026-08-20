package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #128 검증원장 상세 조회 — 실제 DB에 자기 데이터를 만들고(롤백) 헤더·라인·합계·균형여부·
 * 원분개/역분개 양방향 링크가 제대로 조립되는지 확인한다.
 */
@SpringBootTest
@Transactional
class JournalDetailServiceImplIntegrationTest {

    @Autowired
    private JournalDetailService journalDetailService;
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

    private Long insertHeader(String journalNo, String journalType, Long contractId, Long reversalOfId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, reversal_of_id, description)
                VALUES (?, ?, ?, 'TEST', ?, ?, ?, '테스트 분개')
                RETURNING journal_header_id
                """, Long.class, journalNo, LocalDate.of(2026, 8, 1), journalType, journalNo,
                contractId, reversalOfId);
    }

    private Long insertReversalHeader(String journalNo, Long contractId, Long originalId) {
        String correctionGroupKey = "TEST-CORRECTION-" + originalId;
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_correction_group
                    (correction_group_key, original_journal_header_id, reason)
                VALUES (?, ?, 'detail service integration test')
                """, correctionGroupKey, originalId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, reversal_of_id, correction_group_key, description)
                VALUES (?, ?, 'REVERSAL', 'JOURNAL_HEADER', ?, ?, ?, ?, '역분개 조회 테스트')
                RETURNING journal_header_id
                """, Long.class, journalNo, LocalDate.of(2026, 8, 1), originalId.toString(),
                contractId, originalId, correctionGroupKey);
    }

    private Long insertRepostHeader(String journalNo, Long originalId) {
        String correctionGroupKey = jdbcTemplate.queryForObject("""
                SELECT correction_group_key
                  FROM fgc.journal_correction_group
                 WHERE original_journal_header_id = ?
                """, String.class, originalId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, revision_no, correction_group_key, description)
                SELECT ?, ?, original.journal_type, original.source_entity_type, original.source_entity_id,
                       original.contract_id, original.revision_no + 1, ?, '재기표 조회 테스트'
                  FROM fgc.journal_header original
                 WHERE original.journal_header_id = ?
                RETURNING journal_header_id
                """, Long.class, journalNo, LocalDate.of(2026, 8, 1),
                correctionGroupKey, originalId);
    }

    private void insertLine(Long headerId, int lineNo, String accountCode,
                             BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, ?, ?, ?, ?)
                """, headerId, lineNo, journalAccountId(accountCode), debit, credit);
    }

    /** DRAFT -> POSTED 전이는 균형(차변=대변>0)일 때만 허용된다(guard_journal_header_write,
     * V1__baseline_v2_1_2.sql:1349-1356) — 이 헬퍼를 부르기 전에 균형 라인을 먼저 넣어야 한다. */
    private void postJournal(Long headerId) {
        jdbcTemplate.update("UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?", headerId);
    }

    /** POSTED -> REVERSED 전이는 그 분개를 가리키는 역분개(reversal_of_id)가 이미
     * POSTED여야만 허용된다(같은 트리거, 1357-1363행) — reversalHeaderId를 먼저 POSTED로
     * 만든 뒤에 originalHeaderId를 REVERSED로 돌려야 한다. */
    private void markReversed(Long headerId) {
        jdbcTemplate.update("UPDATE fgc.journal_header SET status = 'REVERSED' WHERE journal_header_id = ?", headerId);
    }

    @Test
    void returnsBalancedHeaderWithLinesInOrder() {
        Long contractId = anyContractId();
        String contractNo = jdbcTemplate.queryForObject(
                "SELECT contract_no FROM fgc.insurance_contract WHERE contract_id = ?", String.class, contractId);
        Long headerId = insertHeader("TEST-DETAIL-0001", "ADJUSTMENT", contractId, null);
        insertLine(headerId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("50000.00"), BigDecimal.ZERO);
        insertLine(headerId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("50000.00"));

        JournalDetailResponse response = journalDetailService.findByJournalHeaderId(headerId);

        assertThat(response.journalHeaderId()).isEqualTo(headerId);
        assertThat(response.contractId()).isEqualTo(contractId);
        // 계약 식별은 계약번호로만 한다 — insurance_contract 자체에 계약자 이름·주민번호
        // 컬럼이 없어(V1__baseline_v2_1_2.sql:622-645) 구조적으로 노출될 수 없다.
        assertThat(response.contractNo()).isEqualTo(contractNo);
        assertThat(response.journalType().name()).isEqualTo("ADJUSTMENT");
        assertThat(response.journalTypeLabel()).isEqualTo("조정");
        assertThat(response.status().name()).isEqualTo("DRAFT");
        assertThat(response.debitTotal()).isEqualByComparingTo("50000.00");
        assertThat(response.creditTotal()).isEqualByComparingTo("50000.00");
        assertThat(response.differenceAmount()).isEqualByComparingTo("0");
        assertThat(response.balanced()).isTrue();
        assertThat(response.lines()).hasSize(2);
        assertThat(response.lines().get(0).lineNo()).isEqualTo(1);
        assertThat(response.lines().get(1).lineNo()).isEqualTo(2);
    }

    @Test
    void lineLessHeaderIsNotBalancedEvenThoughDifferenceIsZero() {
        Long contractId = anyContractId();
        Long headerId = insertHeader("TEST-DETAIL-0002", "ADJUSTMENT", contractId, null);

        JournalDetailResponse response = journalDetailService.findByJournalHeaderId(headerId);

        assertThat(response.debitTotal()).isEqualByComparingTo("0");
        assertThat(response.creditTotal()).isEqualByComparingTo("0");
        assertThat(response.differenceAmount()).isEqualByComparingTo("0");
        assertThat(response.balanced()).isFalse();
        assertThat(response.lines()).isEmpty();
    }

    @Test
    void reversalLinkIsExposedBidirectionally() {
        Long contractId = anyContractId();
        Long originalId = insertHeader("TEST-DETAIL-0003", "ADJUSTMENT", contractId, null);
        insertLine(originalId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("10000.00"), BigDecimal.ZERO);
        insertLine(originalId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("10000.00"));
        postJournal(originalId);
        Long reversalId = insertReversalHeader("TEST-DETAIL-0004", contractId, originalId);
        insertLine(reversalId, 1, "EXPECTED_RECEIVABLE", BigDecimal.ZERO, new BigDecimal("10000.00"));
        insertLine(reversalId, 2, "EXPECTED_INCOME", new BigDecimal("10000.00"), BigDecimal.ZERO);
        postJournal(reversalId);
        Long repostedId = insertRepostHeader("TEST-DETAIL-REPOST-0004", originalId);

        JournalDetailResponse original = journalDetailService.findByJournalHeaderId(originalId);
        JournalDetailResponse reversal = journalDetailService.findByJournalHeaderId(reversalId);

        assertThat(original.reversalOfId()).isNull();
        assertThat(original.reversedByJournalHeaderId()).isEqualTo(reversalId);
        assertThat(original.reversedByJournalNo()).isEqualTo("TEST-DETAIL-0004");
        assertThat(original.correctionGroupKey()).isEqualTo("TEST-CORRECTION-" + originalId);
        assertThat(original.repostedJournalHeaderId()).isEqualTo(repostedId);
        assertThat(original.repostedJournalNo()).isEqualTo("TEST-DETAIL-REPOST-0004");

        assertThat(reversal.reversalOfId()).isEqualTo(originalId);
        assertThat(reversal.reversalOfJournalNo()).isEqualTo("TEST-DETAIL-0003");
        assertThat(reversal.reversedByJournalHeaderId()).isNull();
    }

    @Test
    void postedHeaderIsRetrievable() {
        Long contractId = anyContractId();
        Long headerId = insertHeader("TEST-DETAIL-0005", "ADJUSTMENT", contractId, null);
        insertLine(headerId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("10000.00"), BigDecimal.ZERO);
        insertLine(headerId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("10000.00"));
        postJournal(headerId);

        JournalDetailResponse response = journalDetailService.findByJournalHeaderId(headerId);

        assertThat(response.status().name()).isEqualTo("POSTED");
        assertThat(response.statusLabel()).isEqualTo("기표됨");
    }

    @Test
    void reversedHeaderIsRetrievableWithReversalLink() {
        Long contractId = anyContractId();
        Long originalId = insertHeader("TEST-DETAIL-0006", "ADJUSTMENT", contractId, null);
        insertLine(originalId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("10000.00"), BigDecimal.ZERO);
        insertLine(originalId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("10000.00"));
        postJournal(originalId);

        Long reversalId = insertReversalHeader("TEST-DETAIL-0007", contractId, originalId);
        insertLine(reversalId, 1, "EXPECTED_RECEIVABLE", BigDecimal.ZERO, new BigDecimal("10000.00"));
        insertLine(reversalId, 2, "EXPECTED_INCOME", new BigDecimal("10000.00"), BigDecimal.ZERO);
        postJournal(reversalId);
        markReversed(originalId);

        JournalDetailResponse original = journalDetailService.findByJournalHeaderId(originalId);
        JournalDetailResponse reversal = journalDetailService.findByJournalHeaderId(reversalId);

        assertThat(original.status().name()).isEqualTo("REVERSED");
        assertThat(original.statusLabel()).isEqualTo("역분개됨");
        assertThat(original.reversedByJournalHeaderId()).isEqualTo(reversalId);
        assertThat(reversal.reversalOfId()).isEqualTo(originalId);
        assertThat(reversal.reversalOfJournalNo()).isEqualTo("TEST-DETAIL-0006");
    }

    @Test
    void lineCommissionItemIdIsResolvedToItemName() {
        Long contractId = anyContractId();
        Long headerId = insertHeader("TEST-DETAIL-0008", "ADJUSTMENT", contractId, null);
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1", Long.class);
        String itemName = jdbcTemplate.queryForObject(
                "SELECT item_name FROM fgc.commission_item WHERE commission_item_id = ?", String.class, commissionItemId);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount, commission_item_id)
                VALUES (?, 1, ?, 10000.00, 0, ?)
                """, headerId, journalAccountId("EXPECTED_RECEIVABLE"), commissionItemId);

        JournalDetailResponse response = journalDetailService.findByJournalHeaderId(headerId);

        assertThat(response.lines()).singleElement().satisfies(line -> {
            assertThat(line.commissionItemId()).isEqualTo(commissionItemId);
            assertThat(line.commissionItemName()).isEqualTo(itemName);
        });
    }

    @Test
    void unknownJournalHeaderIdThrowsNotFound() {
        assertThatThrownBy(() -> journalDetailService.findByJournalHeaderId(999_999_999L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_004));
    }
}
