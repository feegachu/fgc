package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.dto.JournalBalanceSummary;
import com.susukkang.fgc.journal.dto.JournalDetailHeaderRow;
import com.susukkang.fgc.journal.dto.JournalDetailLineRow;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.LedgerImbalanceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL에 만든 분개로 조회 조건·집계·DTO 매핑을 검증하고 매 테스트 후 롤백한다. */
@SpringBootTest
@Transactional
class JournalQueryRepositoryIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2085, 11, 15);

    @Autowired
    private JournalQueryRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contractId;
    private String accountCode;
    private Long accountId;
    private Long creditAccountId;
    private final Map<Long, String> journalNumbers = new HashMap<>();

    @BeforeEach
    void createFixtureAccount() {
        contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        // 고유 계정으로 검색 범위를 제한하여 개발 DB에 이미 있는 분개와 섞이지 않게 한다.
        accountCode = "JQR_" + UUID.randomUUID().toString().replace("-", "");
        accountId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_account (account_code, account_name, normal_balance)
                VALUES (?, 'Repository 검증 계정', 'DEBIT') RETURNING journal_account_id
                """, Long.class, accountCode);
        creditAccountId = jdbcTemplate.queryForObject("""
                SELECT journal_account_id FROM fgc.journal_account WHERE account_code = 'EXPECTED_INCOME'
                """, Long.class);
    }

    @Test
    void accountFilterReturnsOneHeaderAndTotalsIncludeNonMatchingAccounts() {
        Long headerId = insertHeader(DATE, "ADJUSTMENT", contractId, null);
        insertLine(headerId, 1, accountId, "30000.25", "0");
        insertLine(headerId, 2, accountId, "20000.75", "0");
        insertLine(headerId, 3, creditAccountId, "0", "50001.00");

        List<JournalListRow> rows = searchAndAssertCount(null, null, null, accountCode, contractId, null, 0, 20, 1);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getJournalHeaderId()).isEqualTo(headerId);
            assertThat(row.getDebitTotal()).isEqualByComparingTo("50001.00");
            assertThat(row.getCreditTotal()).isEqualByComparingTo("50001.00");
            assertThat(row.getStatus()).isEqualTo("DRAFT");
            assertThat(row.getJournalNo()).isEqualTo(journalNumbers.get(headerId));
            assertThat(row.getJournalDate()).isEqualTo(DATE);
            assertThat(row.getJournalType()).isEqualTo("ADJUSTMENT");
            assertThat(row.getSourceEntityType()).isEqualTo("TEST");
            assertThat(row.getSourceEntityId()).isEqualTo(journalNumbers.get(headerId));
            assertThat(row.getContractId()).isEqualTo(contractId);
            assertThat(row.getContractNo()).isEqualTo(contractNo());
            assertThat(row.getReversalOfId()).isNull();
            assertThat(row.getReversalOfJournalNo()).isNull();
            assertThat(row.getCreatedBy()).isNull();
            assertThat(row.getPostedBy()).isNull();
            assertThat(row.getPostedAt()).isNull();
            assertThat(row.getCreatedAt().toInstant()).isEqualTo(headerTimestamp(headerId, "created_at").toInstant());
        });
        assertThat(repository.count(null, null, null, accountCode, contractId, null)).isEqualTo(1);
    }

    @Test
    void filtersRetainInclusiveDatesAndDistinguishNullFromEmptyOrUnknownCodes() {
        Long matching = insertHeader(DATE, "ADJUSTMENT", contractId, null);
        Long otherType = insertHeader(DATE, "CLAWBACK", contractId, null);
        Long otherDate = insertHeader(DATE.minusDays(1), "ADJUSTMENT", contractId, null);
        Long noContract = insertHeader(DATE, "ADJUSTMENT", null, null);
        for (Long id : List.of(matching, otherType, otherDate, noContract)) {
            insertLine(id, 1, accountId, "10", "0");
        }

        assertThat(searchAndAssertCount(DATE, DATE, "ADJUSTMENT", accountCode, contractId, "DRAFT", 0, 20, 1))
                .extracting(JournalListRow::getJournalHeaderId).containsExactly(matching);
        assertThat(searchAndAssertCount(null, DATE.minusDays(1), null, accountCode, null, null, 0, 20, 1))
                .extracting(JournalListRow::getJournalHeaderId).containsExactly(otherDate);
        assertThat(searchAndAssertCount(DATE.plusDays(1), null, null, accountCode, null, null, 0, 20, 0)).isEmpty();
        assertThat(searchAndAssertCount(null, null, null, accountCode, null, null, 0, 20, 4)).hasSize(4);
        for (String type : List.of("", "UNKNOWN", "adjustment")) {
            assertThat(searchAndAssertCount(null, null, type, accountCode, null, null, 0, 20, 0)).isEmpty();
        }
        for (String status : List.of("", "UNKNOWN", "draft", "POSTED")) {
            assertThat(searchAndAssertCount(null, null, null, accountCode, null, status, 0, 20, 0)).isEmpty();
        }
        assertThat(searchAndAssertCount(DATE, DATE, null, "", contractId, null, 0, 20, 0)).isEmpty();
    }

    @Test
    void pagingUsesDescendingDateThenIdWithoutChangingTotalCount() {
        Long older = insertHeader(DATE.minusDays(1), "ADJUSTMENT", contractId, null);
        Long first = insertHeader(DATE, "ADJUSTMENT", contractId, null);
        Long second = insertHeader(DATE, "ADJUSTMENT", contractId, null);
        for (Long id : List.of(older, first, second)) {
            insertLine(id, 1, accountId, "10", "0");
        }

        assertThat(searchAndAssertCount(null, null, null, accountCode, null, null, 0, 20, 3))
                .extracting(JournalListRow::getJournalHeaderId).containsExactly(second, first, older);
        assertThat(searchAndAssertCount(null, null, null, accountCode, null, null, 1, 1, 3))
                .extracting(JournalListRow::getJournalHeaderId).containsExactly(first);
        assertThat(searchAndAssertCount(null, null, null, accountCode, null, null, 3, 1, 3)).isEmpty();
        assertThat(repository.count(null, null, null, accountCode, null, null)).isEqualTo(3);
    }

    @Test
    void noLinesAndMissingHeaderPreserveZeroTotalsAndNullableResults() {
        Long headerId = insertHeader(DATE, "ADJUSTMENT", null, null);

        JournalDetailHeaderRow header = repository.findHeaderById(headerId);
        assertThat(header.getJournalHeaderId()).isEqualTo(headerId);
        assertThat(header.getJournalNo()).isEqualTo(journalNumbers.get(headerId));
        assertThat(header.getContractId()).isNull();
        assertThat(header.getContractNo()).isNull();
        assertThat(header.getCreatedBy()).isNull();
        assertThat(header.getPostedBy()).isNull();
        assertThat(header.getPostedAt()).isNull();
        assertThat(header.getValidationRunId()).isNull();
        assertThat(header.getPolicyVersionId()).isNull();
        assertThat(header.getCorrectionGroupKey()).isNull();
        assertThat(header.getReversalOfId()).isNull();
        assertThat(header.getReversalOfJournalNo()).isNull();
        assertThat(header.getReversedByJournalHeaderId()).isNull();
        assertThat(header.getReversedByJournalNo()).isNull();
        assertThat(header.getRepostedJournalHeaderId()).isNull();
        assertThat(header.getRepostedJournalNo()).isNull();
        assertThat(repository.findLinesByHeaderId(headerId)).isEmpty();
        assertThat(repository.findBalanceSummary(headerId)).isNull();
        assertThat(repository.search(DATE, DATE, null, null, null, null, 0, Integer.MAX_VALUE))
                .filteredOn(row -> row.getJournalHeaderId().equals(headerId)).singleElement().satisfies(row -> {
                    assertThat(row.getDebitTotal()).isEqualByComparingTo("0");
                    assertThat(row.getCreditTotal()).isEqualByComparingTo("0");
                });
        assertThat(repository.findHeaderById(-1L)).isNull();
        assertThat(repository.findLinesByHeaderId(-1L)).isEmpty();
        assertThat(repository.findBalanceSummary(-1L)).isNull();
    }

    @Test
    void detailMapsJoinedNamesEnumsDecimalsAndTimestampsWithNullableLineDimensions() {
        Long runId = insertValidationRun(DATE.withDayOfMonth(1));
        Long headerId = insertHeader(DATE, "ADJUSTMENT", contractId, runId);
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1", Long.class);
        Long userId = jdbcTemplate.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        String loginId = jdbcTemplate.queryForObject("SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, userId);
        Long agentId = jdbcTemplate.queryForObject("SELECT agent_id FROM fgc.agent ORDER BY agent_id LIMIT 1", Long.class);
        Long itemId = jdbcTemplate.queryForObject("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1", Long.class);
        // 삽입 순서와 표시 순서를 다르게 하여 line_no 정렬도 검증한다.
        insertLine(headerId, 2, creditAccountId, "0", "12345.67");
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
                     contract_id, agent_id, payment_stage, commission_item_id, memo)
                VALUES (?, 1, ?, 12345.67, 0, ?, ?, 'GA_TO_FC', ?, '소수점 금액 검증')
                """, headerId, accountId, contractId, agentId, itemId);
        jdbcTemplate.update("""
                UPDATE fgc.journal_header SET created_by = ?, posted_by = ?, policy_version_id = ?
                 WHERE journal_header_id = ?
                """, userId, userId, policyVersionId, headerId);
        post(headerId);

        JournalDetailHeaderRow header = repository.findHeaderById(headerId);
        List<JournalDetailLineRow> lines = repository.findLinesByHeaderId(headerId);
        assertThat(header.getJournalHeaderId()).isEqualTo(headerId);
        assertThat(header.getJournalNo()).isEqualTo(journalNumbers.get(headerId));
        assertThat(header.getJournalDate()).isEqualTo(DATE);
        assertThat(header.getJournalType()).isEqualTo("ADJUSTMENT");
        assertThat(header.getSourceEntityType()).isEqualTo("TEST");
        assertThat(header.getSourceEntityId()).isEqualTo(journalNumbers.get(headerId));
        assertThat(header.getContractId()).isEqualTo(contractId);
        assertThat(header.getValidationRunId()).isEqualTo(runId);
        assertThat(header.getPolicyVersionId()).isEqualTo(policyVersionId);
        assertThat(header.getDescription()).isEqualTo("Repository 조회 검증");
        assertThat(header.getStatus()).isEqualTo("POSTED");
        assertThat(header.getRevisionNo()).isEqualTo(1);
        assertThat(header.getCreatedBy()).isEqualTo(loginId);
        assertThat(header.getPostedBy()).isEqualTo(loginId);
        assertThat(header.getCreatedAt().toInstant()).isEqualTo(headerTimestamp(headerId, "created_at").toInstant());
        assertThat(header.getPostedAt().toInstant()).isEqualTo(headerTimestamp(headerId, "posted_at").toInstant());
        assertThat(header.getContractNo()).isEqualTo(contractNo());
        assertThat(lines).extracting(JournalDetailLineRow::getLineNo).containsExactly(1, 2);
        assertThat(lines.get(0).getPaymentStage()).isEqualTo("GA_TO_FC");
        assertThat(lines.get(0).getNormalBalance()).isEqualTo("DEBIT");
        assertThat(lines.get(0).getDebitAmount()).isEqualByComparingTo("12345.67");
        assertThat(lines.get(0).getCreditAmount()).isEqualByComparingTo("0");
        assertThat(lines.get(0).getAccountCode()).isEqualTo(accountCode);
        assertThat(lines.get(0).getAccountName()).isEqualTo("Repository 검증 계정");
        assertThat(lines.get(0).getContractId()).isEqualTo(contractId);
        assertThat(lines.get(0).getAgentId()).isEqualTo(agentId);
        assertThat(lines.get(0).getAgentName()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT agent_name FROM fgc.agent WHERE agent_id = ?", String.class, agentId));
        assertThat(lines.get(0).getCommissionItemId()).isEqualTo(itemId);
        assertThat(lines.get(0).getCommissionItemName()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT item_name FROM fgc.commission_item WHERE commission_item_id = ?", String.class, itemId));
        assertThat(lines.get(0).getMemo()).isEqualTo("소수점 금액 검증");
        assertThat(lines.get(1).getNormalBalance()).isEqualTo("CREDIT");
        assertThat(lines.get(1).getAccountCode()).isEqualTo("EXPECTED_INCOME");
        assertThat(lines.get(1).getDebitAmount()).isEqualByComparingTo("0");
        assertThat(lines.get(1).getCreditAmount()).isEqualByComparingTo("12345.67");
        assertThat(lines.get(1).getContractId()).isNull();
        assertThat(lines.get(1).getAgentId()).isNull();
        assertThat(lines.get(1).getCommissionItemId()).isNull();
        assertThat(lines.get(1).getMemo()).isNull();
        assertThat(lines.get(1).getPaymentStage()).isNull();
        assertThat(lines.get(1).getAgentName()).isNull();
        assertThat(lines.get(1).getCommissionItemName()).isNull();
        assertThat(searchAndAssertCount(DATE, DATE, "ADJUSTMENT", accountCode, contractId, "POSTED", 0, 20, 1))
                .singleElement().satisfies(row -> {
                    assertThat(row.getCreatedBy()).isEqualTo(loginId);
                    assertThat(row.getPostedBy()).isEqualTo(loginId);
                    assertThat(row.getPostedAt()).isNotNull();
                });
    }

    @Test
    void detailRetainsOriginalReversalAndRepostLinksThroughCorrectionGroup() {
        Long originalId = insertHeader(DATE, "ADJUSTMENT", contractId, null);
        insertLine(originalId, 1, accountId, "10", "0");
        insertLine(originalId, 2, creditAccountId, "0", "10");
        post(originalId);
        String groupKey = "JQR-CORRECTION-" + originalId;
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_correction_group (correction_group_key, original_journal_header_id, reason)
                VALUES (?, ?, 'Repository 정정 연결 검증')
                """, groupKey, originalId);
        String reversalNo = journalNo();
        Long reversalId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, reversal_of_id, correction_group_key)
                VALUES (?, ?, 'REVERSAL', 'JOURNAL_HEADER', ?, ?, ?, ?) RETURNING journal_header_id
                """, Long.class, reversalNo, DATE, originalId.toString(), contractId, originalId, groupKey);
        insertLine(reversalId, 1, accountId, "0", "10");
        insertLine(reversalId, 2, creditAccountId, "10", "0");
        post(reversalId);
        String repostNo = journalNo();
        Long repostId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, revision_no, correction_group_key)
                SELECT ?, ?, journal_type, source_entity_type, source_entity_id,
                       contract_id, revision_no + 1, ?
                  FROM fgc.journal_header WHERE journal_header_id = ? RETURNING journal_header_id
                """, Long.class, repostNo, DATE, groupKey, originalId);

        JournalDetailHeaderRow original = repository.findHeaderById(originalId);
        JournalDetailHeaderRow reversal = repository.findHeaderById(reversalId);
        JournalDetailHeaderRow repost = repository.findHeaderById(repostId);
        assertThat(original.getCorrectionGroupKey()).isEqualTo(groupKey);
        assertThat(original.getReversedByJournalHeaderId()).isEqualTo(reversalId);
        assertThat(original.getReversedByJournalNo()).isEqualTo(reversalNo);
        assertThat(original.getRepostedJournalHeaderId()).isEqualTo(repostId);
        assertThat(original.getRepostedJournalNo()).isEqualTo(repostNo);
        assertThat(original.getReversalOfId()).isNull();
        assertThat(reversal.getReversalOfId()).isEqualTo(originalId);
        assertThat(reversal.getReversalOfJournalNo()).isEqualTo(original.getJournalNo());
        assertThat(reversal.getRepostedJournalHeaderId()).isEqualTo(repostId);
        assertThat(reversal.getJournalNo()).isEqualTo(reversalNo);
        assertThat(reversal.getJournalType()).isEqualTo("REVERSAL");
        assertThat(reversal.getCorrectionGroupKey()).isEqualTo(groupKey);
        assertThat(repost.getJournalHeaderId()).isEqualTo(repostId);
        assertThat(repost.getJournalNo()).isEqualTo(repostNo);
        assertThat(repost.getJournalType()).isEqualTo("ADJUSTMENT");
        assertThat(repost.getStatus()).isEqualTo("DRAFT");
        assertThat(repost.getRevisionNo()).isEqualTo(2);
        assertThat(repost.getSourceEntityType()).isEqualTo(original.getSourceEntityType());
        assertThat(repost.getSourceEntityId()).isEqualTo(original.getSourceEntityId());
        assertThat(repost.getCorrectionGroupKey()).isEqualTo(groupKey);
        assertThat(repost.getReversalOfId()).isNull();
        assertThat(repost.getReversedByJournalHeaderId()).isNull();
        assertThat(repost.getRepostedJournalHeaderId()).isEqualTo(repostId);
        assertThat(repost.getRepostedJournalNo()).isEqualTo(repostNo);
        assertThat(searchAndAssertCount(DATE, DATE, "REVERSAL", accountCode, contractId, null, 0, 20, 1))
                .singleElement().satisfies(row -> assertThat(row.getReversalOfId()).isEqualTo(originalId));
    }

    @Test
    void imbalanceQueriesIncludeDraftsAndPreserveSignedDifferenceWithinRun() {
        Long runId = insertValidationRun(DATE.withDayOfMonth(1));
        Long otherRunId = insertValidationRun(DATE.withDayOfMonth(1).plusMonths(1));
        Long debitExcess = insertHeader(DATE, "ADJUSTMENT", contractId, runId);
        insertLine(debitExcess, 1, accountId, "15.25", "0");
        insertLine(debitExcess, 2, creditAccountId, "0", "10.00");
        Long creditExcess = insertHeader(DATE, "CLAWBACK", contractId, runId);
        insertLine(creditExcess, 1, accountId, "10.00", "0");
        insertLine(creditExcess, 2, creditAccountId, "0", "18.50");
        Long balanced = insertHeader(DATE, "ADJUSTMENT", contractId, runId);
        insertLine(balanced, 1, accountId, "20", "0");
        insertLine(balanced, 2, creditAccountId, "0", "20");
        post(balanced);
        insertHeader(DATE, "ADJUSTMENT", contractId, runId);
        Long other = insertHeader(DATE, "ADJUSTMENT", contractId, otherRunId);
        insertLine(other, 1, accountId, "999", "0");

        List<LedgerImbalanceRow> rows = repository.findImbalances(runId);
        assertThat(rows).extracting(LedgerImbalanceRow::getJournalHeaderId).containsExactly(debitExcess, creditExcess);
        assertThat(rows).extracting(LedgerImbalanceRow::getStatus).containsOnly("DRAFT");
        assertThat(rows.get(0).getDifferenceAmount()).isEqualByComparingTo("5.25");
        assertThat(rows.get(1).getDifferenceAmount()).isEqualByComparingTo("-8.50");
        assertThat(rows.get(0).getJournalNo()).isEqualTo(journalNumbers.get(debitExcess));
        assertThat(rows.get(0).getJournalType()).isEqualTo("ADJUSTMENT");
        assertThat(rows.get(0).getDebitTotal()).isEqualByComparingTo("15.25");
        assertThat(rows.get(0).getCreditTotal()).isEqualByComparingTo("10.00");
        assertThat(rows.get(1).getJournalNo()).isEqualTo(journalNumbers.get(creditExcess));
        assertThat(rows.get(1).getJournalType()).isEqualTo("CLAWBACK");
        assertThat(rows.get(1).getDebitTotal()).isEqualByComparingTo("10.00");
        assertThat(rows.get(1).getCreditTotal()).isEqualByComparingTo("18.50");
        assertThat(repository.countImbalances(runId)).isEqualTo(2);
        assertThat(repository.countJournals(runId)).isEqualTo(4);
        assertThat(repository.findImbalances(-1L)).isEmpty();
        assertThat(repository.countImbalances(-1L)).isZero();
        assertThat(repository.countJournals(-1L)).isZero();
    }

    @Test
    void balanceSummaryAggregatesAllLinesForOnlyRequestedHeader() {
        Long target = insertHeader(DATE, "ADJUSTMENT", contractId, null);
        insertLine(target, 1, accountId, "3.25", "0");
        insertLine(target, 2, accountId, "6.75", "0");
        insertLine(target, 3, creditAccountId, "0", "10.00");
        Long other = insertHeader(DATE, "ADJUSTMENT", contractId, null);
        insertLine(other, 1, accountId, "999", "0");

        JournalBalanceSummary summary = repository.findBalanceSummary(target);
        assertThat(summary.getJournalHeaderId()).isEqualTo(target);
        assertThat(summary.getDebitTotal()).isEqualByComparingTo("10.00");
        assertThat(summary.getCreditTotal()).isEqualByComparingTo("10.00");
        assertThat(summary.isBalanced()).isTrue();
    }

    private List<JournalListRow> searchAndAssertCount(LocalDate from, LocalDate to, String type, String account,
                                                     Long contract, String status, int offset, int limit, long total) {
        assertThat(repository.count(from, to, type, account, contract, status))
                .isEqualTo(total);
        return repository.search(from, to, type, account, contract, status, offset, limit);
    }

    private String contractNo() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_no FROM fgc.insurance_contract WHERE contract_id = ?", String.class, contractId);
    }

    private OffsetDateTime headerTimestamp(Long headerId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM fgc.journal_header WHERE journal_header_id = ?",
                OffsetDateTime.class, headerId);
    }

    private Long insertHeader(LocalDate date, String type, Long contract, Long runId) {
        String no = journalNo();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, validation_run_id, description)
                VALUES (?, ?, ?, 'TEST', ?, ?, ?, 'Repository 조회 검증') RETURNING journal_header_id
                """, Long.class, no, date, type, no, contract, runId);
        journalNumbers.put(id, no);
        return id;
    }

    private void insertLine(Long headerId, int number, Long account, String debit, String credit) {
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, ?, ?, ?, ?)
                """, headerId, number, account, new BigDecimal(debit), new BigDecimal(credit));
    }

    private Long insertValidationRun(LocalDate month) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month = ? RETURNING validation_run_id
                """, Long.class, month, month);
    }

    private void post(Long headerId) {
        jdbcTemplate.update("UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?", headerId);
    }

    private String journalNo() {
        return "JQR-" + UUID.randomUUID();
    }
}
