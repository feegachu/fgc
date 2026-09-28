package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.exceptioncase.entity.ExceptionAction;
import com.susukkang.fgc.exceptioncase.entity.ExceptionCase;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseQueryMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #376 공통 예외 엔티티의 DB 기본값·잠금 후 최신 상태·불변 이력 계약을 검증한다. */
@SpringBootTest
@Transactional
class ExceptionPersistenceRepositoryIntegrationTest {

    @Autowired
    private ExceptionCaseRepository caseRepository;

    @Autowired
    private ExceptionActionRepository actionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExceptionCaseQueryMapper queryMapper;

    @Test
    void caseSavePreservesNullableReferencesAndDatabaseDetectionDefaults() {
        String key = "IT-376:" + UUID.randomUUID() + "k".repeat(457);
        OffsetDateTime dueAt = OffsetDateTime.parse("2026-10-01T10:20:30+09:00");
        ExceptionCase saved = caseRepository.saveAndFlush(ExceptionCase.builder()
                .exceptionKey(key)
                .exceptionType(ExceptionType.JOURNAL_CORRECTION_REQUIRED)
                .severity(ExceptionSeverity.HIGH)
                .sourceEntityType("JOURNAL_HEADER")
                .sourceEntityId(UUID.randomUUID().toString())
                .validationMonth(LocalDate.of(2026, 9, 1))
                .reasonCode("JOURNAL_CORRECTION_REQUIRED")
                .title("정정 요청")
                .description("정정 요청 사유")
                .dueAt(dueAt)
                .build());
        entityManager.clear();

        ExceptionCase loaded = caseRepository.findById(saved.getExceptionCaseId()).orElseThrow();
        assertThat(loaded.getExceptionKey()).hasSize(500).isEqualTo(key);
        assertThat(loaded.getExceptionType()).isEqualTo(ExceptionType.JOURNAL_CORRECTION_REQUIRED);
        assertThat(loaded.getSeverity()).isEqualTo(ExceptionSeverity.HIGH);
        assertThat(loaded.getStatus()).isEqualTo(ExceptionStatus.NEW);
        assertThat(loaded.getValidationMonth()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(loaded.getReasonCode()).isEqualTo("JOURNAL_CORRECTION_REQUIRED");
        assertThat(loaded.getDueAt()).isEqualTo(dueAt);
        assertThat(loaded.getDetectionCount()).isEqualTo(1);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getFirstDetectedAt()).isNotNull();
        assertThat(loaded.getLastDetectedAt()).isNotNull();
        assertThat(loaded.getValidationRunId()).isNull();
        assertThat(loaded.getContractId()).isNull();
        assertThat(loaded.getAgentId()).isNull();
        assertThat(loaded.getPolicyVersionId()).isNull();
        assertThat(loaded.getAssignedTo()).isNull();
        assertThat(loaded.getResolvedAt()).isNull();
        assertThat(loaded.getFirstDetectedRunId()).isNull();
        assertThat(loaded.getLastDetectedRunId()).isNull();
        assertThat(loaded.getCapCheckId()).isNull();
    }

    @Test
    void actionSaveKeepsExplicitTimestampAndNullableFromStatus() {
        ExceptionCase target = saveCase();
        OffsetDateTime actionAt = OffsetDateTime.parse("2026-09-28T16:48:12.123456+09:00");
        ExceptionAction saved = actionRepository.saveAndFlush(ExceptionAction.builder()
                .exceptionCaseId(target.getExceptionCaseId())
                .actionSeq(1)
                .toStatus(ExceptionStatus.NEW)
                .actionType(ExceptionActionType.COMMENT)
                .reason("r".repeat(2000))
                .actionBy(firstUserId())
                .actionAt(actionAt)
                .build());
        entityManager.clear();

        ExceptionAction loaded = actionRepository.findById(saved.getExceptionActionId()).orElseThrow();
        assertThat(loaded.getExceptionCaseId()).isEqualTo(target.getExceptionCaseId());
        assertThat(loaded.getActionSeq()).isEqualTo(1);
        assertThat(loaded.getFromStatus()).isNull();
        assertThat(loaded.getToStatus()).isEqualTo(ExceptionStatus.NEW);
        assertThat(loaded.getActionType()).isEqualTo(ExceptionActionType.COMMENT);
        assertThat(loaded.getReason()).hasSize(2000);
        assertThat(loaded.getEvidenceRef()).isNull();
        assertThat(loaded.getActionBy()).isEqualTo(firstUserId());
        assertThat(loaded.getActionAt()).isEqualTo(actionAt);
        assertThat(actionRepository.findNextActionSeq(target.getExceptionCaseId())).isEqualTo(2);
    }

    @Test
    void initialCommentWithoutTimestampUsesDatabaseClockDefault() {
        ExceptionCase target = saveCase();
        Long actionId = actionRepository.saveAndFlush(action(target.getExceptionCaseId(), 1))
                .getExceptionActionId();
        entityManager.clear();

        ExceptionAction loaded = actionRepository.findById(actionId).orElseThrow();
        assertThat(loaded.getActionAt()).isNotNull();
        assertThat(loaded.getFromStatus()).isEqualTo(ExceptionStatus.NEW);
        assertThat(loaded.getActionType()).isEqualTo(ExceptionActionType.COMMENT);
    }

    @Test
    void existingMyBatisHistoryQuerySeesJpaWritesInTheSameTransaction() {
        ExceptionCase target = saveCase();
        ExceptionAction saved = actionRepository.saveAndFlush(action(target.getExceptionCaseId(), 1));

        var rows = queryMapper.findActionsByCaseIds(List.of(target.getExceptionCaseId()));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.exceptionActionId()).isEqualTo(saved.getExceptionActionId());
            assertThat(row.exceptionCaseId()).isEqualTo(target.getExceptionCaseId());
            assertThat(row.actionSeq()).isEqualTo(1);
            assertThat(row.fromStatus()).isEqualTo("NEW");
            assertThat(row.toStatus()).isEqualTo("NEW");
            assertThat(row.actionType()).isEqualTo("COMMENT");
            assertThat(row.actionBy()).isEqualTo(firstUserId());
            assertThat(row.actionByLoginId()).isNotBlank();
            assertThat(row.actionAt()).isNotNull();
            assertThat(row.evidenceRef()).isNull();
        });
    }

    @Test
    void lockedDtoReadsBulkAndJdbcChangesEvenWhileOldEntityRemainsManaged() {
        ExceptionCase managed = saveCase();
        Long id = managed.getExceptionCaseId();
        Long userId = firstUserId();
        OffsetDateTime resolvedAt = OffsetDateTime.parse("2026-09-28T17:00:00+09:00");

        assertThat(caseRepository.updateCaseAfterAction(id, ExceptionStatus.RESOLVED, userId, resolvedAt))
                .isEqualTo(1);
        assertThat(managed.getStatus()).isEqualTo(ExceptionStatus.NEW);
        var resolved = caseRepository.findByIdForUpdate(id);
        assertThat(resolved.status()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(resolved.assignedTo()).isEqualTo(userId);

        jdbcTemplate.update("UPDATE fgc.exception_case SET status = 'IN_REVIEW' WHERE exception_case_id = ?", id);
        var correctionTarget = caseRepository.findJournalCorrectionTargetForUpdate(id);
        assertThat(correctionTarget.status()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(correctionTarget.sourceEntityType()).isEqualTo(managed.getSourceEntityType());
        assertThat(correctionTarget.sourceEntityId()).isEqualTo(managed.getSourceEntityId());

        assertThat(caseRepository.updateCaseAfterAction(id, ExceptionStatus.IN_REVIEW, null, null)).isEqualTo(1);
        entityManager.refresh(managed);
        assertThat(managed.getAssignedTo()).isNull();
        assertThat(managed.getResolvedAt()).isNull();
        assertThat(managed.getUpdatedAt()).isNotNull();
    }

    @Test
    void unknownCaseHasNoLockTargetAndUpdatingItAffectsZeroRows() {
        assertThat(caseRepository.findByIdForUpdate(-1L)).isNull();
        assertThat(caseRepository.findJournalCorrectionTargetForUpdate(-1L)).isNull();
        assertThat(caseRepository.updateCaseAfterAction(-1L, ExceptionStatus.NEW, null, null)).isZero();
        assertThat(actionRepository.findNextActionSeq(-1L)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockingRequiresTheCallersTransactionSoTheLockOutlivesTheQuery() {
        assertThatThrownBy(() -> caseRepository.findByIdForUpdate(-1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void duplicateActionSequenceIsRejectedByTheExistingUniqueConstraint() {
        Long id = saveCase().getExceptionCaseId();
        actionRepository.saveAndFlush(action(id, 1));

        assertThatThrownBy(() -> actionRepository.saveAndFlush(action(id, 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uq_exception_action_seq");
    }

    @Test
    void databaseStillRejectsUpdatingAnActionWrittenThroughJpa() {
        Long actionId = actionRepository.saveAndFlush(action(saveCase().getExceptionCaseId(), 1))
                .getExceptionActionId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fgc.exception_action SET reason = '변경' WHERE exception_action_id = ?", actionId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseStillRejectsDeletingAnActionThroughTheRepository() {
        ExceptionAction saved = actionRepository.saveAndFlush(action(saveCase().getExceptionCaseId(), 1));

        assertThatThrownBy(() -> {
            actionRepository.delete(saved);
            actionRepository.flush();
        }).isInstanceOf(DataAccessException.class);
    }

    @Test
    void activeJournalCorrectionUniquenessAlsoAppliesToEntityInsertsWithNullPolicy() {
        ExceptionCase first = saveCase();

        assertThatThrownBy(() -> caseRepository.saveAndFlush(caseWithSource(first.getSourceEntityId())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uq_exception_active_journal_correction");
    }

    @Test
    void closedJournalCorrectionAllowsANewCaseForTheSameSource() {
        ExceptionCase first = saveCase();
        caseRepository.findByIdForUpdate(first.getExceptionCaseId());
        caseRepository.updateCaseAfterAction(
                first.getExceptionCaseId(), ExceptionStatus.REJECTED, null, OffsetDateTime.now());

        ExceptionCase next = caseRepository.saveAndFlush(caseWithSource(first.getSourceEntityId()));
        assertThat(next.getExceptionCaseId()).isNotEqualTo(first.getExceptionCaseId());
        assertThat(caseRepository.findByIdForUpdate(next.getExceptionCaseId()).status())
                .isEqualTo(ExceptionStatus.NEW);
    }

    private ExceptionCase saveCase() {
        return caseRepository.saveAndFlush(caseWithSource(UUID.randomUUID().toString()));
    }

    private ExceptionCase caseWithSource(String sourceId) {
        return ExceptionCase.builder()
                .exceptionKey("IT-376:" + UUID.randomUUID())
                .exceptionType(ExceptionType.JOURNAL_CORRECTION_REQUIRED)
                .severity(ExceptionSeverity.HIGH)
                .sourceEntityType("JOURNAL_HEADER")
                .sourceEntityId(sourceId)
                .title("공통 예외 Repository 검증")
                .build();
    }

    private ExceptionAction action(Long caseId, int seq) {
        return ExceptionAction.builder()
                .exceptionCaseId(caseId)
                .actionSeq(seq)
                .fromStatus(ExceptionStatus.NEW)
                .toStatus(ExceptionStatus.NEW)
                .actionType(ExceptionActionType.COMMENT)
                .reason("정정 요청 최초 이력")
                .actionBy(firstUserId())
                .build();
    }

    private Long firstUserId() {
        return jdbcTemplate.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
    }
}
