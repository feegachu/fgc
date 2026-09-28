package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionInsertCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL 멱등 INSERT, 정책 버전의 null 비교와 정정 예외 DTO 매핑을 검증한다. */
@SpringBootTest
@Transactional
class JournalCorrectionExceptionRepositoryIntegrationTest {

    @Autowired
    private JournalCorrectionExceptionRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String keyPrefix = "TEST-374-CORRECTION-EXCEPTION-" + UUID.randomUUID();
    private final long sourceId = System.nanoTime();
    private Long userId;
    private Long policyVersionId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1",
                Long.class);
    }

    @Test
    void duplicateKeySkipsInsertionAndInitialActionKeepsEvidenceAndUser() {
        JournalCorrectionExceptionInsertCommand command = command("FIRST", null);

        assertThat(repository.insertCase(command)).isEqualTo(1);
        assertThat(repository.insertCase(command)).isZero();
        var row = repository.findByExceptionKey(command.exceptionKey());
        assertThat(row.status()).isEqualTo(ExceptionStatus.NEW);
        assertThat(repository.findActiveBySource(sourceId, null)).isEqualTo(row);
        assertThat(repository.countBySource(sourceId, null)).isEqualTo(1);

        Map<String, Object> storedCase = jdbcTemplate.queryForMap("""
                SELECT exception_type, reason_code, severity, validation_month,
                       validation_run_id, contract_id, policy_version_id, title, description
                  FROM fgc.exception_case WHERE exception_case_id = ?
                """, row.exceptionCaseId());
        assertThat(storedCase)
                .containsEntry("exception_type", "JOURNAL_CORRECTION_REQUIRED")
                .containsEntry("reason_code", "JOURNAL_CORRECTION_REQUIRED")
                .containsEntry("severity", "HIGH")
                .containsEntry("validation_run_id", null)
                .containsEntry("contract_id", null)
                .containsEntry("policy_version_id", null)
                .containsEntry("title", "정정 요청 테스트")
                .containsEntry("description", "금액 정정 사유");
        assertThat(storedCase.get("validation_month").toString()).isEqualTo("2094-08-01");

        assertThat(repository.insertInitialAction(command)).isEqualTo(1);
        Map<String, Object> action = jdbcTemplate.queryForMap("""
                SELECT action_seq, from_status, to_status, action_type, reason, evidence_ref, action_by
                  FROM fgc.exception_action WHERE exception_case_id = ?
                """, row.exceptionCaseId());
        assertThat(action)
                .containsEntry("action_seq", 1)
                .containsEntry("from_status", "NEW")
                .containsEntry("to_status", "NEW")
                .containsEntry("action_type", "COMMENT")
                .containsEntry("reason", "금액 정정 사유")
                .containsEntry("evidence_ref", "DOC-CORRECTION")
                .containsEntry("action_by", userId);

        var locked = repository.findJournalCorrectionTargetForUpdate(row.exceptionCaseId());
        assertThat(locked.exceptionCaseId()).isEqualTo(row.exceptionCaseId());
        assertThat(locked.exceptionType()).isEqualTo("JOURNAL_CORRECTION_REQUIRED");
        assertThat(locked.status()).isEqualTo(ExceptionStatus.NEW);
        assertThat(locked.sourceEntityType()).isEqualTo("JOURNAL_HEADER");
        assertThat(locked.sourceEntityId()).isEqualTo(String.valueOf(sourceId));
    }

    @Test
    void activeLookupAndLifecycleCountDistinguishNullPolicyFromSpecificPolicy() {
        JournalCorrectionExceptionInsertCommand closed = command("CLOSED", null);
        assertThat(repository.insertCase(closed)).isEqualTo(1);
        var closedRow = repository.findByExceptionKey(closed.exceptionKey());
        jdbcTemplate.update("UPDATE fgc.exception_case SET status = 'REJECTED' WHERE exception_case_id = ?",
                closedRow.exceptionCaseId());
        assertThat(repository.findActiveBySource(sourceId, null)).isNull();

        JournalCorrectionExceptionInsertCommand withoutPolicy = command("WITHOUT-POLICY", null);
        JournalCorrectionExceptionInsertCommand withPolicy = command("WITH-POLICY", policyVersionId);
        assertThat(repository.insertCase(withoutPolicy)).isEqualTo(1);
        assertThat(repository.insertCase(withPolicy)).isEqualTo(1);
        var nullPolicyRow = repository.findByExceptionKey(withoutPolicy.exceptionKey());
        var policyRow = repository.findByExceptionKey(withPolicy.exceptionKey());
        jdbcTemplate.update("UPDATE fgc.exception_case SET status = 'IN_REVIEW' WHERE exception_case_id = ?",
                policyRow.exceptionCaseId());

        assertThat(repository.countBySource(sourceId, null)).isEqualTo(2);
        assertThat(repository.countBySource(sourceId, policyVersionId)).isEqualTo(1);
        assertThat(repository.findActiveBySource(sourceId, null)).isEqualTo(nullPolicyRow);
        var activePolicy = repository.findActiveBySource(sourceId, policyVersionId);
        assertThat(activePolicy.exceptionCaseId()).isEqualTo(policyRow.exceptionCaseId());
        assertThat(activePolicy.status()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(repository.findByExceptionKey(closed.exceptionKey()).status())
                .isEqualTo(ExceptionStatus.REJECTED);
    }

    private JournalCorrectionExceptionInsertCommand command(String suffix, Long policyId) {
        return JournalCorrectionExceptionInsertCommand.builder()
                .exceptionKey(keyPrefix + ":" + suffix)
                .validationMonth(LocalDate.of(2094, 8, 1))
                .policyVersionId(policyId)
                .journalHeaderId(sourceId)
                .title("정정 요청 테스트")
                .description("금액 정정 사유")
                .reason("금액 정정 사유")
                .evidenceRef("DOC-CORRECTION")
                .requestedBy(userId)
                .build();
    }
}
