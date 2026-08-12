package com.susukkang.fgc.cap.mapper;

import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.cap.service.CapExceptionService;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : FGC-FUN-034 한도 예외 자연키·해결조치 Mapper 통합 테스트
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@SpringBootTest
@Transactional
class CapExceptionMapperIntegrationTest {

    @Autowired
    private CapExceptionMapper capExceptionMapper;

    @Autowired
    private CapExceptionService capExceptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void updatesSameViolationWithoutDuplicateAndResolvesWithAction() {
        TestReference reference = testReference();
        Long paymentId = insertDraftPayment(reference);
        String exceptionKey = "CAP_VIOLATION:null:COMMISSION_TRANSACTION:"
                + paymentId + ":GA_TO_FC";

        capExceptionMapper.insertException(exception(
                exceptionKey, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.HIGH));
        capExceptionMapper.insertException(exception(
                exceptionKey, paymentId, reference, ExceptionType.CAP_VIOLATION, ExceptionSeverity.CRITICAL));

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT exception_case_id, exception_type, severity, status
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
                .containsEntry("status", "NEW");
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

        int resolvedDuplicateRows = capExceptionMapper.insertException(exception(
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
