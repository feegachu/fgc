package com.susukkang.fgc.audit.repository;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설명 : 테스트 트랜잭션 밖에서 실제 커밋/롤백 결과를 읽어 JPA·MyBatis의 연결 공유를 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@SpringBootTest
class AuditLogTransactionIntegrationTest {
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ContractMapper contractMapper;
    @Autowired
    private AuditLogRepository repository;
    @Autowired
    private AuditLogService service;
    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate transaction;
    private String marker;

    /**
     * 설명 : JPA 트랜잭션 관리자를 확인하고 테스트 트랜잭션과 고유 식별자를 준비한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @BeforeEach
    void setUp() {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        transaction = new TransactionTemplate(transactionManager);
        marker = "AUD-TX-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 설명 : MyBatis 업무 저장과 서비스·Repository를 통한 JPA 감사 저장이 함께 커밋되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void commitsMyBatisBusinessAndJpaAuditsTogether() {
        InsuranceContract contract = contract();
        try {
            transaction.executeWithoutResult(status -> {
                assertThat(contractMapper.insertContract(contract)).isEqualTo(1);
                record(marker);
                repository.saveAndFlush(auditLog(marker + "-direct"));
            });
            assertThat(contractCount()).isEqualTo(1);
            assertThat(auditCount(marker)).isEqualTo(1);
            assertThat(auditCount(marker + "-direct")).isEqualTo(1);
        } finally {
            // 이 테스트가 직접 생성한 업무행만 정리한다. 감사행은 추가 기록 전용이므로 삭제하지 않는다.
            jdbc.update("delete from fgc.insurance_contract where contract_no = ?", marker);
        }
    }

    /**
     * 설명 : 업무 실패 시 업무 데이터와 두 저장 경로의 감사로그가 함께 롤백되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void rollsBackBusinessAndBothJpaAuditCallsWhenBusinessFails() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            contractMapper.insertContract(contract());
            record(marker);
            repository.saveAndFlush(auditLog(marker + "-direct"));
            throw new IllegalStateException("business failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("business failed");

        assertThat(contractCount()).isZero();
        assertThat(auditCount(marker)).isZero();
        assertThat(auditCount(marker + "-direct")).isZero();
    }

    /**
     * 설명 : 감사 저장 오류가 업무 데이터와 앞서 저장한 감사로그까지 롤백시키는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void auditDatabaseFailureRollsBackBusinessAndEarlierAudit() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            contractMapper.insertContract(contract());
            record(marker);
            service.record(AuditLogService.AuditEvent.builder().entityType("TEST_TX").entityId(marker)
                    .actionCode("X".repeat(51)).build());
        })).isInstanceOf(DataAccessException.class);

        assertThat(contractCount()).isZero();
        assertThat(auditCount(marker)).isZero();
    }

    /**
     * 설명 : 외부 업무가 롤백되어도 호출자가 별도 트랜잭션으로 커밋한 감사로그는 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void requiresNewCallerKeepsItsCommittedAuditWhenOuterBusinessRollsBack() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            contractMapper.insertContract(contract());
            record(marker);
            requiresNew.executeWithoutResult(inner -> record(marker + "-new"));
            status.setRollbackOnly();
        });

        assertThat(contractCount()).isZero();
        assertThat(auditCount(marker)).isZero();
        assertThat(auditCount(marker + "-new")).isEqualTo(1);
    }

    /**
     * 설명 : 외부 트랜잭션이 없어도 Repository가 트랜잭션을 시작해 감사로그를 커밋하는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    @Test
    void commitsAuditWithoutCallerTransaction() {
        record(marker);

        assertThat(auditCount(marker)).isEqualTo(1);
    }

    /**
     * 설명 : 현재 업무 트랜잭션에 참여하는 테스트 감사로그를 기록한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private void record(String entityId) {
        service.record(AuditLogService.AuditEvent.builder().actionCode("TEST_TX")
                .entityType("TEST_TX").entityId(entityId).build());
    }

    /**
     * 설명 : 트랜잭션 검증에 사용할 신규 감사 엔티티를 생성한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private AuditLog auditLog(String entityId) {
        return AuditLog.create(null, "TEST_TX", "TEST_TX", entityId, null, null, null, null, null, null);
    }

    /**
     * 설명 : 현재 테스트 식별자로 저장된 계약 건수를 조회한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private long contractCount() {
        return jdbc.queryForObject("select count(*) from fgc.insurance_contract where contract_no = ?",
                Long.class, marker);
    }

    /**
     * 설명 : 지정한 대상 식별자의 트랜잭션 테스트 감사로그 건수를 조회한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private long auditCount(String entityId) {
        return jdbc.queryForObject("select count(*) from fgc.audit_log where entity_type = 'TEST_TX' and entity_id = ?",
                Long.class, entityId);
    }

    /**
     * 설명 : 기존 계약의 필수 정보를 복사해 독립적인 테스트 계약 객체를 생성한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private InsuranceContract contract() {
        Long id = jdbc.queryForObject("select contract_id from fgc.insurance_contract order by contract_id limit 1",
                Long.class);
        InsuranceContract source = contractMapper.selectContractById(id);
        return InsuranceContract.builder()
                .contractNo(marker).insurerId(source.getInsurerId()).productOfferingId(source.getProductOfferingId())
                .contractDate(source.getContractDate()).agentId(source.getAgentId())
                .organizationId(source.getOrganizationId()).premiumPerCycleAmount(source.getPremiumPerCycleAmount())
                .firstPremiumAmount(source.getFirstPremiumAmount())
                .monthlyEquivalentFirstPremium(source.getMonthlyEquivalentFirstPremium())
                .premiumConversionRuleCode(source.getPremiumConversionRuleCode())
                .paymentCycleCode(source.getPaymentCycleCode()).paymentTermMonths(source.getPaymentTermMonths())
                .standardSurrenderDeductionAmount(source.getStandardSurrenderDeductionAmount())
                .currentStatus(source.getCurrentStatus()).dataOrigin(source.getDataOrigin()).build();
    }
}
