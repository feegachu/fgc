package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import com.susukkang.fgc.journal.entity.JournalHeader;
import com.susukkang.fgc.journal.repository.JournalHeaderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

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
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @MockitoSpyBean
    private JournalHeaderRepository journalHeaderRepository;

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
        JournalHeaderDraft baseDraft = newDraft(scheduleLineId);
        Long agentId = jdbcTemplate.queryForObject("SELECT agent_id FROM fgc.agent ORDER BY agent_id LIMIT 1", Long.class);
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1", Long.class);
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1", Long.class);
        JournalLineDraft debitLine = baseDraft.getLines().get(0).toBuilder()
                .agentId(agentId).commissionItemId(itemId).memo("JPA 저장 매핑 검증").build();
        JournalHeaderDraft draft = baseDraft.toBuilder().policyVersionId(policyVersionId)
                .lines(List.of(debitLine, baseDraft.getLines().get(1))).build();

        JournalHeaderRow saved = journalPersistenceService.saveDraft(draft, 3L, "req-93-normal");

        assertThat(saved.getJournalHeaderId()).isNotNull();
        assertThat(saved.getJournalNo()).matches("JV-\\d{4}-\\d{2}-\\d{4}");
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getJournalDate()).isEqualTo(journalDate);
        assertThat(saved.getJournalType()).isEqualTo(draft.getJournalType().name());
        assertThat(saved.getSourceEntityType()).isEqualTo(draft.getSourceEntityType());
        assertThat(saved.getSourceEntityId()).isEqualTo(draft.getSourceEntityId());
        assertThat(saved.getRevisionNo()).isEqualTo(draft.getRevisionNo());
        assertThat(saved.getContractId()).isEqualTo(draft.getContractId());
        assertThat(saved.getPolicyVersionId()).isEqualTo(policyVersionId);
        assertThat(saved.getDescription()).isEqualTo(TEST_MARKER);
        assertThat(saved.getCreatedBy()).isEqualTo(3L);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedAt().toInstant()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT created_at FROM fgc.journal_header WHERE journal_header_id = ?",
                OffsetDateTime.class, saved.getJournalHeaderId()).toInstant());
        assertThat(saved.getPostedBy()).isNull();
        assertThat(saved.getPostedAt()).isNull();

        Long debitTotal = jdbcTemplate.queryForObject(
                "SELECT SUM(debit_amount)::bigint FROM fgc.journal_line WHERE journal_header_id = ?",
                Long.class, saved.getJournalHeaderId());
        Long creditTotal = jdbcTemplate.queryForObject(
                "SELECT SUM(credit_amount)::bigint FROM fgc.journal_line WHERE journal_header_id = ?",
                Long.class, saved.getJournalHeaderId());
        assertThat(debitTotal).isEqualTo(50_000L).isEqualTo(creditTotal);
        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                SELECT line_no, contract_id, agent_id, payment_stage, commission_item_id, memo
                  FROM fgc.journal_line WHERE journal_header_id = ? ORDER BY line_no
                """, saved.getJournalHeaderId());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("line_no", 1)
                .containsEntry("contract_id", draft.getContractId())
                .containsEntry("agent_id", agentId)
                .containsEntry("payment_stage", "INSURER_TO_GA")
                .containsEntry("commission_item_id", itemId)
                .containsEntry("memo", "JPA 저장 매핑 검증");
        assertThat(lines.get(1)).containsEntry("line_no", 2)
                .containsEntry("agent_id", null).containsEntry("commission_item_id", null);

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
        assertThat(second.getJournalNo()).isEqualTo(first.getJournalNo());
        assertThat(second.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_line WHERE journal_header_id = ?",
                Long.class, first.getJournalHeaderId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.audit_log
                 WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ? AND action_code = 'JOURNAL_DRAFT_SAVED'
                """, Long.class, first.getJournalHeaderId().toString())).isEqualTo(1);
    }

    @Test
    void outerTransactionRollbackDoesNotUndoCommittedDraft() {
        JournalHeaderDraft draft = newDraft(System.nanoTime());
        JournalHeaderRow saved = new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return journalPersistenceService.saveDraft(draft, 3L, "req-93-outer-rollback");
        });

        assertThat(saved).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.journal_header WHERE journal_header_id = ?",
                String.class, saved.getJournalHeaderId())).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_line WHERE journal_header_id = ?",
                Long.class, saved.getJournalHeaderId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.audit_log
                 WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ? AND request_id = 'req-93-outer-rollback'
                """, Long.class, saved.getJournalHeaderId().toString())).isEqualTo(1);
    }

    @Test
    void journalNumberCollisionRetriesWithANewTransactionAfterDatabaseRollback() {
        JournalHeaderRow occupied = journalPersistenceService.saveDraft(newDraft(System.nanoTime()), 3L, "req-93-occupied-no");
        int occupiedSequence = Integer.parseInt(occupied.getJournalNo().substring(occupied.getJournalNo().lastIndexOf('-') + 1));
        String year = String.valueOf(journalDate.getYear());
        String month = String.format("%02d", journalDate.getMonthValue());
        List<Long> transactionIds = new ArrayList<>();
        // 첫 시도만 이미 사용한 번호로 채번해 실제 uq_journal_no 위반과 PostgreSQL rollback을 유도한다.
        // 실패한 트랜잭션을 재사용하면 다음 SELECT 자체가 aborted transaction 오류를 낸다.
        doAnswer(invocation -> {
            transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
            return transactionIds.size() == 1 ? occupiedSequence : occupiedSequence + 1;
        }).when(journalHeaderRepository).findNextJournalSeq(year, month);
        JournalHeaderDraft draft = newDraft(System.nanoTime());

        JournalHeaderRow saved = journalPersistenceService.saveDraft(draft, 3L, "req-93-retry-no");

        assertThat(transactionIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(saved.getJournalNo()).isEqualTo(String.format("JV-%s-%s-%04d", year, month, occupiedSequence + 1));
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_header
                 WHERE journal_type = ? AND source_entity_type = ? AND source_entity_id = ? AND revision_no = ?
                """, Long.class, draft.getJournalType().name(), draft.getSourceEntityType(),
                draft.getSourceEntityId(), draft.getRevisionNo())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_line WHERE journal_header_id = ?",
                Long.class, saved.getJournalHeaderId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.audit_log
                 WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ? AND request_id = 'req-93-retry-no'
                """, Long.class, saved.getJournalHeaderId().toString())).isEqualTo(1);
    }

    @Test
    void postedSourceRejectsNewRevisionBeforeSavingAnyLinesOrAudit() {
        JournalHeaderDraft draft = newDraft(System.nanoTime());
        JournalHeaderRow original = journalPersistenceService.saveDraft(draft, 3L, "req-93-before-post");
        JournalHeaderDraft nextRevision = draft.toBuilder().revisionNo(draft.getRevisionNo() + 1).build();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 기표 상태는 이 외부 트랜잭션만 롤백해 원본 DRAFT를 안전하게 정리할 수 있게 한다.
            status.setRollbackOnly();
            assertThat(journalHeaderRepository.markPosted(original.getJournalHeaderId(), 3L)).isEqualTo(1);
            assertThatThrownBy(() -> journalPersistenceService.saveDraft(nextRevision, 3L, "req-93-posted-reject"))
                    .isInstanceOf(FgcBusinessException.class)
                    .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode()).isEqualTo(FgcErrorCode.LEDG_002));
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fgc.journal_header
                     WHERE journal_type = ? AND source_entity_type = ? AND source_entity_id = ?
                    """, Long.class, draft.getJournalType().name(), draft.getSourceEntityType(), draft.getSourceEntityId()))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.audit_log WHERE request_id = ?",
                    Long.class, "req-93-posted-reject")).isZero();
        });
    }

    @Test
    void idempotentReadReturnsLatestBulkPostedStateDespiteManagedDraft() {
        JournalHeaderDraft draft = newDraft(System.nanoTime());
        JournalHeaderRow original = journalPersistenceService.saveDraft(draft, 3L, "req-93-before-mixed-post");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            JournalHeader managed = entityManager.find(JournalHeader.class, original.getJournalHeaderId());
            assertThat(managed.getStatus()).isEqualTo(JournalHeaderStatus.DRAFT);
            assertThat(journalHeaderRepository.markPosted(original.getJournalHeaderId(), 3L)).isEqualTo(1);

            JournalHeaderRow existing = journalPersistenceService.saveDraft(draft, 3L, "req-93-after-mixed-post");

            assertThat(existing.getJournalHeaderId()).isEqualTo(original.getJournalHeaderId());
            assertThat(existing.getStatus()).isEqualTo("POSTED");
            assertThat(existing.getPostedBy()).isEqualTo(3L);
            assertThat(existing.getPostedAt()).isNotNull();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.audit_log WHERE request_id = ?",
                    Long.class, "req-93-after-mixed-post")).isZero();
        });
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

    /**
     * 설명 : 감사 저장 오류가 REQUIRES_NEW 안에서 앞서 저장한 분개 헤더와 라인까지 롤백시키는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    @Test
    void auditInsertFailureRollsBackHeaderAndLines() {
        JournalHeaderDraft draft = newDraft(System.nanoTime());
        String invalidRequestId = "r".repeat(81);
        Long linesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.journal_line", Long.class);

        assertThatThrownBy(() -> journalPersistenceService.saveDraft(draft, 3L, invalidRequestId))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_header
                 WHERE journal_type = ? AND source_entity_type = ? AND source_entity_id = ? AND revision_no = ?
                """, Long.class, draft.getJournalType().name(), draft.getSourceEntityType(),
                draft.getSourceEntityId(), draft.getRevisionNo())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.journal_line", Long.class))
                .isEqualTo(linesBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.audit_log WHERE request_id = ?",
                Long.class, invalidRequestId)).isZero();
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
