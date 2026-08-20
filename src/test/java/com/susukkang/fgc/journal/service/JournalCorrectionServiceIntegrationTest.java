package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import com.susukkang.fgc.journal.dto.ReverseAndRepostJournalCommand;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionLineRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import com.susukkang.fgc.exceptioncase.service.JournalCorrectionExceptionActionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FGC-FUN-047 차대 역전, 원본 불변, 정정그룹, 중복 방지와 실패 롤백 통합 검증. */
@SpringBootTest
class JournalCorrectionServiceIntegrationTest {

    private static final long ACTOR_ID = 1L;
    private static final LocalDate JOURNAL_DATE = LocalDate.of(2026, 8, 1);

    @Autowired
    private JournalCorrectionService journalCorrectionService;

    @Autowired
    private JournalCorrectionExceptionService journalCorrectionExceptionService;

    @Autowired
    private JournalCorrectionExceptionActionService correctionExceptionActionService;

    @Autowired
    private ExceptionCaseService exceptionCaseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clearRequestId() {
        RequestIdContext.clear();
    }

    @Test
    @Transactional
    void reverseSwapsDebitAndCreditAndKeepsOriginalImmutable() {
        String sourceId = newSourceId();
        Long originalId = insertPostedOriginal(sourceId, BigDecimal.valueOf(650_000));
        RequestIdContext.set("req-fun047-reverse");

        JournalCorrectionResult result = journalCorrectionService.reverse(
                new ReverseJournalCommand(originalId, "원장 금액 정정", "DOC-047", ACTOR_ID));

        assertThat(result.reversalOfId()).isEqualTo(originalId);
        assertThat(result.repostedJournalHeaderId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.journal_header WHERE journal_header_id = ?",
                String.class, originalId)).isEqualTo("REVERSED");

        Map<String, Object> reversal = jdbcTemplate.queryForMap("""
                SELECT journal_type, reversal_of_id, correction_group_key, status
                FROM fgc.journal_header
                WHERE journal_header_id = ?
                """, result.journalHeaderId());
        assertThat(reversal.get("journal_type")).isEqualTo("REVERSAL");
        assertThat(((Number) reversal.get("reversal_of_id")).longValue()).isEqualTo(originalId);
        assertThat(reversal.get("correction_group_key")).isEqualTo(result.correctionGroupKey());
        assertThat(reversal.get("status")).isEqualTo("POSTED");

        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                SELECT line_no, debit_amount, credit_amount
                FROM fgc.journal_line
                WHERE journal_header_id = ?
                ORDER BY line_no
                """, result.journalHeaderId());
        assertThat((BigDecimal) lines.get(0).get("debit_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) lines.get(0).get("credit_amount")).isEqualByComparingTo("650000");
        assertThat((BigDecimal) lines.get(1).get("debit_amount")).isEqualByComparingTo("650000");
        assertThat((BigDecimal) lines.get(1).get("credit_amount")).isEqualByComparingTo("0");

        Map<String, Object> group = jdbcTemplate.queryForMap("""
                SELECT original_journal_header_id, reason, evidence_ref
                FROM fgc.journal_correction_group
                WHERE correction_group_key = ?
                """, result.correctionGroupKey());
        assertThat(((Number) group.get("original_journal_header_id")).longValue())
                .isEqualTo(originalId);
        assertThat(group.get("reason")).isEqualTo("원장 금액 정정");
        assertThat(group.get("evidence_ref")).isEqualTo("DOC-047");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT action_code FROM fgc.audit_log
                WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ? AND request_id = ?
                """, String.class, String.valueOf(originalId), "req-fun047-reverse"))
                .isEqualTo("JOURNAL_REVERSED");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE fgc.journal_header SET description = '원본 변경 시도'
                WHERE journal_header_id = ?
                """, originalId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void reverseAndRepostCreatesThreeMemberCorrectionGroupWithRevisionTwo() {
        String sourceId = newSourceId();
        Long originalId = insertPostedOriginal(sourceId, BigDecimal.valueOf(650_000));
        Long contractId = contractId();
        JournalHeaderDraft correctedDraft = correctedDraft(
                sourceId, contractId, BigDecimal.valueOf(600_000), 1, 2);
        RequestIdContext.set("req-fun047-repost");

        JournalCorrectionResult result = journalCorrectionService.reverseAndRepost(
                new ReverseAndRepostJournalCommand(
                        new ReverseJournalCommand(
                                originalId, "기대 금액 재산정", "CALC-047", ACTOR_ID),
                        correctedDraft));

        assertThat(result.repostedJournalHeaderId()).isNotNull();
        Map<String, Object> repost = jdbcTemplate.queryForMap("""
                SELECT journal_type, source_entity_type, source_entity_id, revision_no,
                       correction_group_key, status
                FROM fgc.journal_header
                WHERE journal_header_id = ?
                """, result.repostedJournalHeaderId());
        assertThat(repost.get("journal_type")).isEqualTo(JournalType.EXPECTED_INSURER_INCOME.name());
        assertThat(repost.get("source_entity_type")).isEqualTo("SCHEDULE_LINE");
        assertThat(repost.get("source_entity_id")).isEqualTo(sourceId);
        assertThat(((Number) repost.get("revision_no")).intValue()).isEqualTo(2);
        assertThat(repost.get("correction_group_key")).isEqualTo(result.correctionGroupKey());
        assertThat(repost.get("status")).isEqualTo("POSTED");

        Integer memberCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM fgc.journal_correction_group g
                JOIN fgc.journal_header h
                  ON h.journal_header_id = g.original_journal_header_id
                  OR h.correction_group_key = g.correction_group_key
                WHERE g.correction_group_key = ?
                """, Integer.class, result.correctionGroupKey());
        assertThat(memberCount).isEqualTo(3);

        Map<String, BigDecimal> netByAccount = jdbcTemplate.query("""
                SELECT a.account_code,
                       SUM(l.debit_amount - l.credit_amount) AS net_amount
                FROM fgc.journal_line l
                JOIN fgc.journal_account a ON a.journal_account_id = l.journal_account_id
                WHERE l.journal_header_id IN (?, ?, ?)
                GROUP BY a.account_code
                """, rs -> {
            java.util.HashMap<String, BigDecimal> values = new java.util.HashMap<>();
            while (rs.next()) {
                values.put(rs.getString("account_code"), rs.getBigDecimal("net_amount"));
            }
            return values;
        }, originalId, result.journalHeaderId(), result.repostedJournalHeaderId());
        assertThat(netByAccount.get(JournalAccountCode.EXPECTED_RECEIVABLE.name()))
                .isEqualByComparingTo("600000");
        assertThat(netByAccount.get(JournalAccountCode.EXPECTED_INCOME.name()))
                .isEqualByComparingTo("-600000");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT action_code FROM fgc.audit_log
                WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ? AND request_id = ?
                """, String.class, String.valueOf(originalId), "req-fun047-repost"))
                .isEqualTo("JOURNAL_REVERSED_AND_REPOSTED");
        Map<String, Object> auditAmounts = jdbcTemplate.queryForMap("""
                SELECT before_value ->> 'debitTotal' AS before_debit,
                       after_value ->> 'debitTotal' AS after_debit
                FROM fgc.audit_log
                WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ? AND request_id = ?
                """, String.valueOf(originalId), "req-fun047-repost");
        assertThat(new BigDecimal((String) auditAmounts.get("before_debit")))
                .isEqualByComparingTo("650000");
        assertThat(new BigDecimal((String) auditAmounts.get("after_debit")))
                .isEqualByComparingTo("600000");
    }

    @Test
    @Transactional
    void exceptionWorkflowReversesRepostsAndResolvesInOneTransaction() {
        Long originalId = insertPostedOriginal(newSourceId(), BigDecimal.valueOf(650_000));
        String loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, ACTOR_ID);
        RequestIdContext.set("req-fun047-exception-flow");

        var created = journalCorrectionExceptionService.createOrGet(
                originalId,
                new JournalCorrectionExceptionRequest("금액 오류 정정", "DOC-047"),
                ACTOR_ID);
        exceptionCaseService.action(
                created.exceptionCaseId(),
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "원분개 검토 시작", "DOC-047"),
                ACTOR_ID,
                loginId);

        var corrected = correctionExceptionActionService.correct(
                created.exceptionCaseId(),
                correctionRequest(BigDecimal.valueOf(620_000), BigDecimal.valueOf(620_000)),
                ACTOR_ID,
                loginId);

        assertThat(corrected.originalJournalHeaderId()).isEqualTo(originalId);
        assertThat(corrected.reversalJournalHeaderId()).isNotNull();
        assertThat(corrected.repostedJournalHeaderId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.journal_header WHERE journal_header_id = ?",
                String.class, originalId)).isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_case_id = ?",
                String.class, created.exceptionCaseId())).isEqualTo("RESOLVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_header
                WHERE correction_group_key = ?
                """, Integer.class, corrected.correctionGroupKey())).isEqualTo(2);
    }

    @Test
    @Transactional
    void failedRepostKeepsOriginalPostedAndExceptionInReview() {
        Long originalId = insertPostedOriginal(newSourceId(), BigDecimal.valueOf(650_000));
        String loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, ACTOR_ID);
        RequestIdContext.set("req-fun047-exception-rollback");
        var created = journalCorrectionExceptionService.createOrGet(
                originalId,
                new JournalCorrectionExceptionRequest("불균형 정정 시도", null),
                ACTOR_ID);
        exceptionCaseService.action(
                created.exceptionCaseId(),
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "검토 시작", null),
                ACTOR_ID,
                loginId);

        assertThatThrownBy(() -> correctionExceptionActionService.correct(
                created.exceptionCaseId(),
                correctionRequest(BigDecimal.valueOf(620_000), BigDecimal.valueOf(610_000)),
                ACTOR_ID,
                loginId))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(error -> assertThat(((FgcBusinessException) error).getErrorCode())
                        .isEqualTo(FgcErrorCode.LEDG_001));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.journal_header WHERE journal_header_id = ?",
                String.class, originalId)).isEqualTo("POSTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_case_id = ?",
                String.class, created.exceptionCaseId())).isEqualTo("IN_REVIEW");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_correction_group
                WHERE original_journal_header_id = ?
                """, Integer.class, originalId)).isZero();
    }

    @Test
    @Transactional
    void rejectedCorrectionRequestCreatesNewActiveCaseAndPreservesHistory() {
        Long originalId = insertPostedOriginal(newSourceId(), BigDecimal.valueOf(650_000));
        String loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, ACTOR_ID);

        var rejected = journalCorrectionExceptionService.createOrGet(
                originalId,
                new JournalCorrectionExceptionRequest("오탐 검토", "DOC-REJECTED"),
                ACTOR_ID);
        exceptionCaseService.action(
                rejected.exceptionCaseId(),
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "오탐 여부 검토", null),
                ACTOR_ID,
                loginId);
        exceptionCaseService.action(
                rejected.exceptionCaseId(),
                new ExceptionActionRequest(
                        ExceptionActionType.REJECT, "이번 요청은 반려", "DOC-REJECTED"),
                ACTOR_ID,
                loginId);

        var recreated = journalCorrectionExceptionService.createOrGet(
                originalId,
                new JournalCorrectionExceptionRequest("새 정정 요청", "DOC-NEW"),
                ACTOR_ID);

        assertThat(recreated.created()).isTrue();
        assertThat(recreated.status()).isEqualTo(ExceptionStatus.NEW);
        assertThat(recreated.exceptionCaseId()).isNotEqualTo(rejected.exceptionCaseId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM fgc.exception_case
                WHERE source_entity_type = 'JOURNAL_HEADER'
                  AND source_entity_id = ?
                  AND exception_type = 'JOURNAL_CORRECTION_REQUIRED'
                """, Integer.class, String.valueOf(originalId))).isEqualTo(2);
    }

    @Test
    @Transactional
    void activeCorrectionCaseIsUniqueAtDatabaseBoundary() {
        // FGC-FUN-052: 정책 버전이 없는 활성 정정 예외도 DB 경계에서 하나만 허용한다.
        Long originalId = insertPostedOriginal(newSourceId(), BigDecimal.valueOf(650_000));
        journalCorrectionExceptionService.createOrGet(
                originalId,
                new JournalCorrectionExceptionRequest("첫 정정 요청", null),
                ACTOR_ID);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, reason_code, severity, status,
                    validation_month, source_entity_type, source_entity_id, title
                ) VALUES (
                    ?, 'JOURNAL_CORRECTION_REQUIRED', 'JOURNAL_CORRECTION_REQUIRED',
                    'HIGH', 'NEW', ?, 'JOURNAL_HEADER', ?, '중복 활성 정정 예외'
                )
                """, "FUN047-DUPLICATE-" + UUID.randomUUID(), JOURNAL_DATE,
                String.valueOf(originalId)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void activeCorrectionCaseWithPolicyVersionIsUniqueAtDatabaseBoundary() {
        // FGC-FUN-052: 정책 버전이 있는 업무키 차원도 V38 부분 UNIQUE 제약으로 보호한다.
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1",
                Long.class);
        Long originalId = insertPostedOriginal(
                newSourceId(), BigDecimal.valueOf(650_000), policyVersionId);
        journalCorrectionExceptionService.createOrGet(
                originalId,
                new JournalCorrectionExceptionRequest("정책 버전 정정 요청", null),
                ACTOR_ID);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, reason_code, severity, status,
                    policy_version_id, validation_month, source_entity_type, source_entity_id, title
                ) VALUES (
                    ?, 'JOURNAL_CORRECTION_REQUIRED', 'JOURNAL_CORRECTION_REQUIRED',
                    'HIGH', 'NEW', ?, ?, 'JOURNAL_HEADER', ?, '정책 버전 중복 활성 정정 예외'
                )
                """, "FGC-FUN-052-DUPLICATE-" + UUID.randomUUID(), policyVersionId,
                JOURNAL_DATE, String.valueOf(originalId)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void repostRoundsEachLineHalfUpAndAllowsChangedLineComposition() {
        // FGC-FUN-047 / 운영정책서 제17조의2: 상세행별 HALF_UP 후 라인 재구성을 허용한다.
        Long originalId = insertPostedOriginal(newSourceId(), BigDecimal.valueOf(650_000));
        String loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, ACTOR_ID);
        var created = journalCorrectionExceptionService.createOrGet(
                originalId,
                new JournalCorrectionExceptionRequest("라인 구성 정정", null),
                ACTOR_ID);
        exceptionCaseService.action(
                created.exceptionCaseId(),
                new ExceptionActionRequest(
                        ExceptionActionType.START_REVIEW, "신규 분개 구성 검토", null),
                ACTOR_ID,
                loginId);

        JournalCorrectionActionRequest request = new JournalCorrectionActionRequest(
                "상세행 반올림 정정", null, JOURNAL_DATE, "FUN-047 라인 재구성",
                List.of(
                        new JournalCorrectionActionLineRequest(
                                1, JournalAccountCode.EXPECTED_RECEIVABLE.name(),
                                new BigDecimal("100.5"), BigDecimal.ZERO, "0.5 올림"),
                        new JournalCorrectionActionLineRequest(
                                2, JournalAccountCode.EXPECTED_RECEIVABLE.name(),
                                new BigDecimal("100.4"), BigDecimal.ZERO, "0.4 절사"),
                        new JournalCorrectionActionLineRequest(
                                null, JournalAccountCode.EXPECTED_INCOME.name(),
                                BigDecimal.ZERO, new BigDecimal("200.5"), "신규 대변 라인")
                ));

        var corrected = correctionExceptionActionService.correct(
                created.exceptionCaseId(), request, ACTOR_ID, loginId);
        List<Map<String, Object>> repostedLines = jdbcTemplate.queryForList("""
                SELECT line_no, debit_amount, credit_amount
                FROM fgc.journal_line
                WHERE journal_header_id = ?
                ORDER BY line_no
                """, corrected.repostedJournalHeaderId());

        assertThat(repostedLines).hasSize(3);
        assertThat((BigDecimal) repostedLines.get(0).get("debit_amount"))
                .isEqualByComparingTo("101");
        assertThat((BigDecimal) repostedLines.get(1).get("debit_amount"))
                .isEqualByComparingTo("100");
        assertThat((BigDecimal) repostedLines.get(2).get("credit_amount"))
                .isEqualByComparingTo("201");
    }

    @Test
    @Transactional
    void duplicateRequestDoesNotCreateAnotherReversal() {
        Long originalId = insertPostedOriginal(newSourceId(), BigDecimal.valueOf(100_000));
        ReverseJournalCommand command = new ReverseJournalCommand(
                originalId, "중복 요청 검증", null, ACTOR_ID);

        journalCorrectionService.reverse(command);

        assertThatThrownBy(() -> journalCorrectionService.reverse(command))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.LEDG_002));

        Integer reversalCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_header WHERE reversal_of_id = ?
                """, Integer.class, originalId);
        assertThat(reversalCount).isEqualTo(1);
    }

    @Test
    void repostLineFailureRollsBackOriginalReversalAndCorrectionGroup() {
        String sourceId = newSourceId();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Long originalId = insertPostedOriginal(sourceId, BigDecimal.valueOf(200_000));
            Long contractId = contractId();
            JournalHeaderDraft brokenDraft = correctedDraft(
                    sourceId, contractId, BigDecimal.valueOf(190_000), 1, 1);

            journalCorrectionService.reverseAndRepost(new ReverseAndRepostJournalCommand(
                    new ReverseJournalCommand(originalId, "롤백 검증", null, ACTOR_ID),
                    brokenDraft));
        })).isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_header
                WHERE source_entity_type = 'SCHEDULE_LINE' AND source_entity_id = ?
                """, Integer.class, sourceId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_correction_group WHERE reason = '롤백 검증'
                """, Integer.class)).isZero();
    }

    private Long insertPostedOriginal(String sourceId, BigDecimal amount) {
        return insertPostedOriginal(sourceId, amount, null);
    }

    private Long insertPostedOriginal(String sourceId, BigDecimal amount, Long policyVersionId) {
        Long contractId = contractId();
        String journalNo = "TEST-CORR-" + UUID.randomUUID();
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type,
                     source_entity_id, revision_no, contract_id, policy_version_id,
                     description, created_by)
                VALUES (?, ?, 'EXPECTED_INSURER_INCOME', 'SCHEDULE_LINE', ?, 1, ?, ?, ?, ?)
                RETURNING journal_header_id
                """, Long.class, journalNo, JOURNAL_DATE, sourceId, contractId, policyVersionId,
                "FUN-047 통합테스트", ACTOR_ID);

        Long debitAccountId = accountId(JournalAccountCode.EXPECTED_RECEIVABLE);
        Long creditAccountId = accountId(JournalAccountCode.EXPECTED_INCOME);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount,
                     credit_amount, contract_id, payment_stage)
                VALUES (?, 1, ?, ?, 0, ?, 'INSURER_TO_GA'),
                       (?, 2, ?, 0, ?, ?, 'INSURER_TO_GA')
                """, headerId, debitAccountId, amount, contractId,
                headerId, creditAccountId, amount, contractId);
        assertThat(jdbcTemplate.update("""
                UPDATE fgc.journal_header SET status = 'POSTED', posted_by = ?
                WHERE journal_header_id = ?
                """, ACTOR_ID, headerId)).isEqualTo(1);
        return headerId;
    }

    private JournalHeaderDraft correctedDraft(String sourceId,
                                               Long contractId,
                                               BigDecimal amount,
                                               int firstLineNo,
                                               int secondLineNo) {
        return JournalHeaderDraft.builder()
                .journalType(JournalType.EXPECTED_INSURER_INCOME)
                .journalDate(JOURNAL_DATE)
                .sourceEntityType("SCHEDULE_LINE")
                .sourceEntityId(sourceId)
                .revisionNo(2)
                .contractId(contractId)
                .description("FUN-047 정정 분개")
                .lines(List.of(
                        JournalLineDraft.builder()
                                .lineNo(firstLineNo)
                                .accountCode(JournalAccountCode.EXPECTED_RECEIVABLE)
                                .debitAmount(amount)
                                .creditAmount(BigDecimal.ZERO)
                                .contractId(contractId)
                                .paymentStage(PaymentStage.INSURER_TO_GA)
                                .build(),
                        JournalLineDraft.builder()
                                .lineNo(secondLineNo)
                                .accountCode(JournalAccountCode.EXPECTED_INCOME)
                                .debitAmount(BigDecimal.ZERO)
                                .creditAmount(amount)
                                .contractId(contractId)
                                .paymentStage(PaymentStage.INSURER_TO_GA)
                                .build()))
                .build();
    }

    private JournalCorrectionActionRequest correctionRequest(
            BigDecimal debitAmount,
            BigDecimal creditAmount
    ) {
        return new JournalCorrectionActionRequest(
                "원장 금액 정정", "DOC-047", JOURNAL_DATE, "FUN-047 정정 분개",
                List.of(
                        new JournalCorrectionActionLineRequest(
                                1, JournalAccountCode.EXPECTED_RECEIVABLE.name(),
                                debitAmount, BigDecimal.ZERO, "정정 차변"),
                        new JournalCorrectionActionLineRequest(
                                2, JournalAccountCode.EXPECTED_INCOME.name(),
                                BigDecimal.ZERO, creditAmount, "정정 대변")
                ));
    }

    private Long contractId() {
        return jdbcTemplate.queryForObject("""
                SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """, Long.class);
    }

    private Long accountId(JournalAccountCode code) {
        return jdbcTemplate.queryForObject("""
                SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?
                """, Long.class, code.name());
    }

    private static String newSourceId() {
        return "FUN047-" + UUID.randomUUID();
    }
}
