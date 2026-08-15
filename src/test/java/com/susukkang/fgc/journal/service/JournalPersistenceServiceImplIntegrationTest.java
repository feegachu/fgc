package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #93 "정상 저장, 라인 저장 실패 롤백, 중복 원천, 비활성 계정과목" 테스트(이슈 To-do).
 *
 * 클래스에 @Transactional을 붙이지 않는다(코드리뷰 반영 — 이전에는 붙어 있었는데,
 * 그러면 @AfterEach의 정리 DELETE까지 테스트 트랜잭션에 같이 묶여서 테스트 종료 시
 * 롤백돼 버렸다. saveDraft()의 실제 저장은 REQUIRES_NEW로 커밋되니, 정리 DELETE도
 * 똑같이 진짜로 커밋돼야 짝이 맞는다 — @Transactional을 빼면 각 jdbcTemplate 호출이
 * 자동커밋되어 @AfterEach가 실제로 지운다). 이 버그 때문에 매 테스트 실행마다
 * FGC-FGL01-202607-0001에 분개가 계속 쌓이고 있었다(#108 작업 중 발견, 확인 시점
 * 기준 30건 누적 — 수동으로 정리함).
 */
@SpringBootTest
class JournalPersistenceServiceImplIntegrationTest {

    private static final String TEST_MARKER = "#93 통합테스트";

    @Autowired
    private JournalPersistenceService journalPersistenceService;
    @Autowired
    private JournalEntryDraftService journalEntryDraftService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final LocalDate journalDate = LocalDate.of(2026, 8, 1);

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("""
                DELETE FROM fgc.journal_line WHERE journal_header_id IN (
                    SELECT journal_header_id FROM fgc.journal_header WHERE description = ?
                )
                """, TEST_MARKER);
        jdbcTemplate.update("DELETE FROM fgc.journal_header WHERE description = ?", TEST_MARKER);
        // inactiveAccountCodeIsRejectedBeforeAnyInsert()가 비활성화시킨 계정과목을
        // 되돌린다 — @Transactional이 없으니 더는 자동 롤백되지 않는다. 그 테스트가 안
        // 돌았어도 이미 true인 값을 다시 true로 세팅할 뿐이라 항상 실행해도 안전하다.
        jdbcTemplate.update("UPDATE fgc.journal_account SET active_yn = true WHERE account_code = ?",
                JournalAccountCode.EXPECTED_RECEIVABLE.name());
    }

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
                .description(TEST_MARKER)
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
    void imbalancedDraftIsRejectedBeforeAnyInsert() {
        // 대변 줄만 금액을 늘려 차변합계≠대변합계로 만든다(코드리뷰 반영 — 저장 시점부터
        // 균형을 강제해야 한다는 FGC-FUN-046 인수조건 확인용).
        JournalHeaderDraft draft = newDraft(System.nanoTime());
        JournalLineDraft inflatedCredit = draft.getLines().get(1).toBuilder()
                .creditAmount(draft.getLines().get(1).getCreditAmount().add(BigDecimal.TEN))
                .build();
        JournalHeaderDraft imbalancedDraft = draft.toBuilder()
                .lines(List.of(draft.getLines().get(0), inflatedCredit))
                .build();

        assertThatThrownBy(() -> journalPersistenceService.saveDraft(imbalancedDraft, 3L, "req-93-imbalance"))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.LEDG_001));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_header WHERE journal_type = ? AND source_entity_type = ? "
                        + "AND source_entity_id = ? AND revision_no = ?",
                Integer.class, imbalancedDraft.getJournalType().name(), imbalancedDraft.getSourceEntityType(),
                imbalancedDraft.getSourceEntityId(), imbalancedDraft.getRevisionNo());
        assertThat(count).isEqualTo(0);
    }

    @Test
    void inactiveAccountCodeIsRejectedBeforeAnyInsert() {
        // @AfterEach의 cleanUp()이 이 UPDATE를 되돌린다(더는 @Transactional 자동
        // 롤백에 기대지 않는다).
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
