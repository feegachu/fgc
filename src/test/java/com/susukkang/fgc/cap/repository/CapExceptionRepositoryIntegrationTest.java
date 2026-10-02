package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.exceptioncase.entity.ExceptionCase;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설명 : FGC-FUN-034 한도 예외 자연키·해결조치 Repository 통합 테스트
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@SpringBootTest
@Transactional
class CapExceptionRepositoryIntegrationTest {

    @Autowired
    private CapExceptionRepository capExceptionRepository;

    @Autowired
    private CapExceptionService capExceptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updatesSameViolationWithoutDuplicateAndResolvesWithAction() {
        TestReference reference = testReference();
        Long paymentId = insertDraftPayment(reference);
        String exceptionKey = "CAP_VIOLATION:null:COMMISSION_TRANSACTION:"
                + paymentId + ":GA_TO_FC:" + reference.capRuleSetId();

        capExceptionRepository.insertException(exception(
                exceptionKey, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.HIGH));
        Long id = jdbcTemplate.queryForObject(
                "SELECT exception_case_id FROM fgc.exception_case WHERE exception_key = ?",
                Long.class, exceptionKey);
        ExceptionCase managed = entityManager.find(ExceptionCase.class, id);
        assertThat(managed.getSeverity()).isEqualTo(ExceptionSeverity.HIGH);
        capExceptionRepository.insertException(exception(
                exceptionKey, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.CRITICAL));
        assertThat(entityManager.find(ExceptionCase.class, id).getSeverity())
                .isEqualTo(ExceptionSeverity.CRITICAL);

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT exception_case_id, exception_type, severity, status, validation_month
                  FROM fgc.exception_case
                 WHERE exception_key = ?
                """, exceptionKey);
        Long exceptionCaseId = ((Number) stored.get("exception_case_id")).longValue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_case WHERE exception_key = ?",
                Integer.class,
                exceptionKey
        )).isEqualTo(1);
        assertThat(stored)
                .containsEntry("exception_type", "CAP_VIOLATION")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("status", "NEW")
                // #330: 실시간 예외도 지급 건의 정산월을 검출 검증월로 남겨
                //       월 통합검증 확정 게이트(제44조)가 셀 수 있어야 한다
                .containsEntry("validation_month", java.sql.Date.valueOf("2026-08-01"));
        assertThat(capExceptionService.hasUnresolvedViolation(paymentId)).isTrue();

        capExceptionService.resolve(CapExceptionResolveCommand.builder()
                .exceptionCaseId(exceptionCaseId)
                .actionType(ExceptionActionType.REDUCE)
                .reason("지급액 감액 완료")
                .evidenceRef("IT-FUN034")
                .actionBy(reference.userId())
                .build());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_case_id = ?",
                String.class,
                exceptionCaseId
        )).isEqualTo("RESOLVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT action_type
                  FROM fgc.exception_action
                 WHERE exception_case_id = ?
                """, String.class, exceptionCaseId)).isEqualTo("REDUCE");
        assertThat(capExceptionService.hasUnresolvedViolation(paymentId)).isFalse();
        assertThat(entityManager.find(ExceptionCase.class, exceptionCaseId).getStatus())
                .isEqualTo(ExceptionStatus.RESOLVED);

        int resolvedDuplicateRows = capExceptionRepository.insertException(exception(
                exceptionKey, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.CRITICAL));

        assertThat(resolvedDuplicateRows).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_case WHERE exception_key = ?",
                Integer.class,
                exceptionKey
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_key = ?",
                String.class,
                exceptionKey
        )).isEqualTo("RESOLVED");
    }

    @Test
    void preservesReviewStatusAndAssigneeWhenCapExceptionIsUpdatedAndResolved() {
        TestReference reference = testReference();
        Long paymentId = insertDraftPayment(reference);
        String key = "IT-CAP-REVIEW:" + UUID.randomUUID();
        capExceptionRepository.insertException(exception(
                key, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.HIGH));
        jdbcTemplate.update("""
                UPDATE fgc.exception_case SET status = 'IN_REVIEW', assigned_to = ?
                 WHERE exception_key = ?
                """, reference.userId(), key);

        capExceptionRepository.insertException(exception(
                key, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.CRITICAL));
        Long id = jdbcTemplate.queryForObject(
                "SELECT exception_case_id FROM fgc.exception_case WHERE exception_key = ?", Long.class, key);
        ExceptionCase reviewed = entityManager.find(ExceptionCase.class, id);
        assertThat(reviewed.getStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);

        CapExceptionResolveCommand command = CapExceptionResolveCommand.builder()
                .exceptionCaseId(id).actionType(ExceptionActionType.REDUCE)
                .reason("감액 처리 완료").actionBy(reference.userId()).build();
        capExceptionService.resolve(command);
        capExceptionService.resolve(command);

        ExceptionCase resolved = entityManager.find(ExceptionCase.class, id);
        assertThat(resolved.getAssignedTo()).isEqualTo(reference.userId());
        assertThat(resolved.getStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT action_seq, from_status, to_status FROM fgc.exception_action
                 WHERE exception_case_id = ?
                """, id))
                .containsEntry("action_seq", 1)
                .containsEntry("from_status", "IN_REVIEW")
                .containsEntry("to_status", "RESOLVED");
    }

    @ParameterizedTest
    @EnumSource(value = ExceptionStatus.class, names = {"RESOLVED", "REJECTED"})
    void preservesClosedCasesForBothCapAndPaymentConflictPaths(ExceptionStatus closedStatus) {
        TestReference reference = testReference();
        Long paymentId = insertDraftPayment(reference);
        String key = "IT-CAP-CLOSED:" + UUID.randomUUID();
        capExceptionRepository.insertException(exception(
                key, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.HIGH));
        jdbcTemplate.update("UPDATE fgc.exception_case SET status = ? WHERE exception_key = ?",
                closedStatus.name(), key);

        assertThat(capExceptionRepository.insertException(exception(
                key, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.CRITICAL))).isZero();
        assertThat(capExceptionRepository.insertExceptionCase(paymentException(key, paymentId, "changed"))).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, severity, title FROM fgc.exception_case WHERE exception_key = ?
                """, key))
                .containsEntry("status", closedStatus.name())
                .containsEntry("severity", "HIGH")
                .containsEntry("title", "통합 테스트 한도 예외");
    }

    @Test
    void paymentExceptionResetsReviewStateAndKeepsNullableSourceFallback() {
        String key = "IT-PAYMENT-EXCEPTION:" + UUID.randomUUID();
        assertThat(capExceptionRepository.insertExceptionCase(paymentException(key, null, "initial"))).isEqualTo(1);
        jdbcTemplate.update("UPDATE fgc.exception_case SET status = 'IN_REVIEW' WHERE exception_key = ?", key);
        Long id = jdbcTemplate.queryForObject(
                "SELECT exception_case_id FROM fgc.exception_case WHERE exception_key = ?", Long.class, key);
        assertThat(entityManager.find(ExceptionCase.class, id).getStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);

        assertThat(capExceptionRepository.insertExceptionCase(paymentException(key, null, "updated"))).isEqualTo(1);

        ExceptionCase stored = entityManager.find(ExceptionCase.class, id);
        assertThat(stored.getStatus()).isEqualTo(ExceptionStatus.NEW);
        assertThat(stored.getSourceEntityId()).isEqualTo("missing-payment-source");
        assertThat(stored.getValidationMonth()).isNull();
        assertThat(stored.getContractId()).isNull();
        assertThat(stored.getAgentId()).isNull();
        assertThat(stored.getTitle()).isEqualTo("updated");
        assertThat(stored.getReasonCode()).isEqualTo("POLICY_MISSING");
        assertThat(capExceptionRepository.selectExceptionForUpdate(id)).isNull();
    }

    @Test
    void failureRecordingReusesCaseAndOccurrenceWithinSameValidationRun() {
        TestReference reference = testReference();
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (DATE '2099-12-01', 373, 'MANUAL_CONTRACT') RETURNING validation_run_id
                """, Long.class);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run SET status = 'RUNNING', started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        Long firstId = capExceptionRepository.recordCapCheckFailure(
                runId, reference.contractId(), "GA_TO_FC", "한도 계산 실패");
        entityManager.find(ExceptionCase.class, firstId);
        Long secondId = capExceptionRepository.recordCapCheckFailure(
                runId, reference.contractId(), "GA_TO_FC", "재시도 실패");

        assertThat(secondId).isEqualTo(firstId);
        ExceptionCase stored = entityManager.find(ExceptionCase.class, firstId);
        assertThat(stored.getExceptionKey()).isEqualTo(
                "DATA_QUALITY:2099-12:CONTRACT:" + reference.contractId() + ":GA_TO_FC");
        assertThat(stored.getReasonCode()).isEqualTo("CAP_CALCULATION_FAILED");
        // 같은 실행에서 재검출하면 최초 occurrence와 설명을 그대로 보존한다.
        assertThat(stored.getDescription()).isEqualTo("한도 계산 실패");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_occurrence
                 WHERE exception_case_id = ? AND validation_run_id = ?
                """, Integer.class, firstId, runId)).isEqualTo(1);
        // MANUAL_CONTRACT는 활성 실행이 하나만 허용되므로 기존 실행을 정상 상태 전이로 종료한다.
        jdbcTemplate.update("""
                UPDATE fgc.validation_run SET status = 'FAILED', completed_at = clock_timestamp(),
                       failure_message = '한도 계산 실패'
                 WHERE validation_run_id = ?
                """, runId);
        Long nextRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (DATE '2099-12-01', 374, 'MANUAL_CONTRACT') RETURNING validation_run_id
                """, Long.class);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run SET status = 'RUNNING', started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, nextRunId);
        assertThat(capExceptionRepository.recordCapCheckFailure(
                nextRunId, reference.contractId(), "GA_TO_FC", "다음 실행 실패")).isEqualTo(firstId);
        assertThat(entityManager.find(ExceptionCase.class, firstId).getDescription()).isEqualTo("다음 실행 실패");
        assertThat(capExceptionRepository.recordCapCheckFailure(
                -373L, reference.contractId(), "GA_TO_FC", "없는 실행")).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresCallerTransactionToKeepExceptionLockThroughActionAndUpdate() {
        assertThatThrownBy(() -> capExceptionRepository.selectExceptionForUpdate(-373L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private ExceptionCaseCommand paymentException(String key, Long paymentId, String title) {
        return ExceptionCaseCommand.builder()
                .exceptionKey(key).exceptionType("POLICY_MISSING").reasonCode("POLICY_MISSING")
                .severity("WARNING").paymentId(paymentId).sourceEntityId("missing-payment-source")
                .title(title).description("지급 예외 회귀 검증").build();
    }

    private CapExceptionInsertDTO exception(
            String exceptionKey,
            Long paymentId,
            TestReference reference,
            ExceptionType exceptionType,
            ExceptionSeverity severity
    ) {
        return CapExceptionInsertDTO.builder()
                .exceptionKey(exceptionKey)
                .exceptionType(exceptionType)
                .severity(severity)
                .contractId(reference.contractId())
                .agentId(reference.agentId())
                .policyVersionId(reference.policyVersionId())
                .paymentId(paymentId)
                .title("통합 테스트 한도 예외")
                .description("FGC-FUN-034 통합 테스트")
                .build();
    }

    private TestReference testReference() {
        return jdbcTemplate.queryForObject("""
                SELECT c.contract_id,
                       a.agent_id,
                       ci.commission_item_id,
                       crs.cap_rule_set_id,
                       crs.policy_version_id,
                       u.user_id
                  FROM fgc.insurance_contract c
                  CROSS JOIN LATERAL (SELECT agent_id FROM fgc.agent ORDER BY agent_id LIMIT 1) a
                  CROSS JOIN LATERAL (SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1) ci
                  CROSS JOIN LATERAL (
                       SELECT cap_rule_set_id, policy_version_id
                         FROM fgc.cap_rule_set
                        ORDER BY cap_rule_set_id
                        LIMIT 1
                  ) crs
                  CROSS JOIN LATERAL (SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1) u
                 ORDER BY c.contract_id
                 LIMIT 1
                """, (resultSet, rowNum) -> new TestReference(
                resultSet.getLong("contract_id"),
                resultSet.getLong("agent_id"),
                resultSet.getLong("commission_item_id"),
                resultSet.getLong("cap_rule_set_id"),
                resultSet.getLong("policy_version_id"),
                resultSet.getLong("user_id")
        ));
    }

    private Long insertDraftPayment(TestReference reference) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage,
                    source_type,
                    source_business_key,
                    recipient_agent_id,
                    commission_item_id,
                    policy_version_id,
                    settlement_month,
                    amount,
                    cashflow_type,
                    status
                ) VALUES (
                    'GA_TO_FC',
                    'GA_MANUAL_PAYMENT',
                    ?,
                    ?,
                    ?,
                    ?,
                    DATE '2026-08-01',
                    0,
                    'PAYMENT',
                    'DRAFT'
                )
                RETURNING commission_transaction_id
                """, Long.class,
                "IT-FUN034-" + UUID.randomUUID(),
                reference.agentId(),
                reference.commissionItemId(),
                reference.policyVersionId());
    }

    private record TestReference(
            Long contractId,
            Long agentId,
            Long commissionItemId,
            Long capRuleSetId,
            Long policyVersionId,
            Long userId
    ) {
    }
}
