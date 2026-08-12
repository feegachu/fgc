package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #93 "정상 저장, 라인 저장 실패 롤백, 중복 원천, 비활성 계정과목" 테스트(이슈 To-do).
 *
 * @Transactional로 테스트 종료 시 자동 롤백시킨다 — journal_header/journal_line은
 * guard_journal_header_write/guard_journal_line_write 트리거가 DRAFT 상태에서는 DELETE를
 * 막지 않지만(POSTED/REVERSED만 막음), 롤백 쪽이 더 간단하고 다른 통합테스트(#77 코드리뷰
 * 반영분)와 관례도 맞는다.
 */
@SpringBootTest
@Transactional
class JournalPersistenceServiceImplIntegrationTest {

    @Autowired
    private JournalPersistenceService journalPersistenceService;
    @Autowired
    private JournalEntryDraftService journalEntryDraftService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final LocalDate journalDate = LocalDate.of(2026, 8, 1);

    private Long contractId() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202607-0001");
    }

    private JournalHeaderDraft newDraft(long scheduleLineId) {
        ExpectedInsurerIncomeJournalCommand command = ExpectedInsurerIncomeJournalCommand.builder()
                .scheduleLineId(scheduleLineId)
                .contractId(contractId())
                .journalDate(journalDate)
                .expectedAmount(BigDecimal.valueOf(50_000))
                .description("#93 통합테스트")
                .build();
        return journalEntryDraftService.draftExpectedInsurerIncome(command);
    }

    @Test
    void savingANewDraftPersistsBalancedHeaderAndLinesWithAuditLog() {
        long scheduleLineId = System.nanoTime();
        JournalHeaderDraft draft = newDraft(scheduleLineId);

        JournalHeaderRow saved = journalPersistenceService.saveDraft(draft, 3L, "req-93-normal");

        assertThat(saved.getJournalHeaderId()).isNotNull();
        assertThat(saved.getJournalNo()).matches("JV-\\d{4}-\\d{2}-\\d{4}");
        assertThat(saved.getStatus()).isEqualTo("DRAFT");

        Long debitTotal = jdbcTemplate.queryForObject(
                "SELECT SUM(debit_amount)::bigint FROM fgc.journal_line WHERE journal_header_id = ?",
                Long.class, saved.getJournalHeaderId());
        Long creditTotal = jdbcTemplate.queryForObject(
                "SELECT SUM(credit_amount)::bigint FROM fgc.journal_line WHERE journal_header_id = ?",
                Long.class, saved.getJournalHeaderId());
        assertThat(debitTotal).isEqualTo(creditTotal);

        List<String> auditActionCodes = jdbcTemplate.queryForList(
                "SELECT action_code FROM fgc.audit_log WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ? AND request_id = ?",
                String.class, String.valueOf(saved.getJournalHeaderId()), "req-93-normal");
        assertThat(auditActionCodes).containsExactly("JOURNAL_DRAFT_SAVED");
    }

    @Test
    void savingTheSameSourceTwiceIsIdempotentAndDoesNotDuplicate() {
        long scheduleLineId = System.nanoTime();
        JournalHeaderDraft draft = newDraft(scheduleLineId);

        JournalHeaderRow first = journalPersistenceService.saveDraft(draft, 3L, "req-93-first");
        JournalHeaderRow second = journalPersistenceService.saveDraft(draft, 3L, "req-93-second");

        assertThat(second.getJournalHeaderId()).isEqualTo(first.getJournalHeaderId());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_header WHERE journal_type = ? AND source_entity_type = ? "
                        + "AND source_entity_id = ? AND revision_no = ?",
                Integer.class, draft.getJournalType().name(), draft.getSourceEntityType(),
                draft.getSourceEntityId(), draft.getRevisionNo());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void lineInsertFailureRollsBackTheHeaderToo() {
        JournalHeaderDraft goodDraft = newDraft(System.nanoTime());
        // 두 줄 모두 lineNo=1로 만들어 uq_journal_line(journal_header_id, line_no) 위반을
        // 유도한다 — 첫 줄 INSERT는 성공하고 두 번째 줄에서 실패해야, "라인 INSERT가
        // 중간에 실패하면 헤더까지 통째로 롤백되는지"를 검증할 수 있다.
        JournalLineDraft duplicateLineNo = goodDraft.getLines().get(0).toBuilder().lineNo(1).build();
        JournalLineDraft alsoLineNo1 = goodDraft.getLines().get(1).toBuilder().lineNo(1).build();
        JournalHeaderDraft brokenDraft = goodDraft.toBuilder()
                .lines(List.of(duplicateLineNo, alsoLineNo1))
                .build();

        assertThatThrownBy(() -> journalPersistenceService.saveDraft(brokenDraft, 3L, "req-93-rollback"))
                .isInstanceOf(DataAccessException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_header WHERE journal_type = ? AND source_entity_type = ? "
                        + "AND source_entity_id = ? AND revision_no = ?",
                Integer.class, brokenDraft.getJournalType().name(), brokenDraft.getSourceEntityType(),
                brokenDraft.getSourceEntityId(), brokenDraft.getRevisionNo());
        assertThat(count).isEqualTo(0);
    }

    @Test
    void inactiveAccountCodeIsRejectedBeforeAnyInsert() {
        // @Transactional 테스트라 이 UPDATE도 테스트 종료 시 자동 롤백된다 —
        // 다른 테스트나 실제 데이터에 영향을 주지 않는다.
        jdbcTemplate.update("UPDATE fgc.journal_account SET active_yn = false WHERE account_code = ?",
                JournalAccountCode.EXPECTED_RECEIVABLE.name());

        JournalHeaderDraft draft = newDraft(System.nanoTime());

        assertThatThrownBy(() -> journalPersistenceService.saveDraft(draft, 3L, "req-93-inactive"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.JOURNAL_001));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_header WHERE journal_type = ? AND source_entity_type = ? "
                        + "AND source_entity_id = ? AND revision_no = ?",
                Integer.class, draft.getJournalType().name(), draft.getSourceEntityType(),
                draft.getSourceEntityId(), draft.getRevisionNo());
        assertThat(count).isEqualTo(0);
    }
}
