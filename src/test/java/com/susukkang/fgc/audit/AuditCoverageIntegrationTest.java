package com.susukkang.fgc.audit;

import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractResponse;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.service.ContractService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FUN-061 인수조건 "핵심 변경 시나리오의 감사로그 기록률 100%" 통합 검증.
 * 각 시나리오를 실제 서비스로 실행하고 같은 트랜잭션 안에서 audit_log 행을 센다 —
 * 운영정책서 제51조 "각 서비스가 같은 트랜잭션에서 감사행을 명시적으로 생성".
 *
 * 지급 건(PAYMENT_*) 시나리오는 확정 게이트 픽스처가 무거워
 * CommissionPaymentServiceImplTest 의 감사 호출 계약 테스트로 검증한다.
 * audit_log 는 append-only 라 {@code @Transactional} 롤백으로만 정리한다.
 */
@SpringBootTest
@Transactional
class AuditCoverageIntegrationTest {

    @Autowired
    private ContractService contractService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("계약 등록·수정은 CONTRACT_CREATED / CONTRACT_UPDATED 감사행을 남긴다")
    void contractCreateAndUpdateLeaveAuditRows() {
        Map<String, Object> reference = jdbcTemplate.queryForMap("""
                SELECT insurer_id, product_offering_id, contract_date, agent_id, organization_id
                  FROM fgc.insurance_contract
                 ORDER BY contract_id
                 LIMIT 1
                """);
        String contractNo = "AUD-" + UUID.randomUUID().toString().substring(0, 12);

        ContractCreateRequest createRequest = new ContractCreateRequest(
                ((Number) reference.get("insurer_id")).longValue(),
                contractNo,
                ((Number) reference.get("product_offering_id")).longValue(),
                ((java.sql.Date) reference.get("contract_date")).toLocalDate(),
                ContractStatus.ACTIVE,
                ((Number) reference.get("agent_id")).longValue(),
                ((Number) reference.get("organization_id")).longValue(),
                PaymentCycleCode.MONTHLY,
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                120,
                BigDecimal.ZERO
        );

        ContractResponse created = contractService.createContract(createRequest);

        assertThat(auditCount("CONTRACT_CREATED", "CONTRACT", String.valueOf(created.getContractId())))
                .isEqualTo(1);
        String afterValue = jdbcTemplate.queryForObject("""
                SELECT after_value::text FROM fgc.audit_log
                 WHERE action_code = 'CONTRACT_CREATED' AND entity_type = 'CONTRACT' AND entity_id = ?
                """, String.class, String.valueOf(created.getContractId()));
        assertThat(afterValue).contains(contractNo);

        ContractUpdateRequest updateRequest = new ContractUpdateRequest(
                contractNo,
                createRequest.getInsurerId(),
                createRequest.getProductOfferingId(),
                createRequest.getContractDate(),
                ContractStatus.LAPSED,
                createRequest.getAgentId(),
                createRequest.getOrganizationId(),
                PaymentCycleCode.MONTHLY,
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                120,
                BigDecimal.ZERO
        );

        contractService.updateContract(created.getContractId(), updateRequest);

        Map<String, Object> updatedAudit = jdbcTemplate.queryForMap("""
                SELECT before_value::text AS before_value, after_value::text AS after_value
                  FROM fgc.audit_log
                 WHERE action_code = 'CONTRACT_UPDATED' AND entity_type = 'CONTRACT' AND entity_id = ?
                """, String.valueOf(created.getContractId()));
        assertThat((String) updatedAudit.get("before_value")).contains("ACTIVE");
        assertThat((String) updatedAudit.get("after_value")).contains("LAPSED");
    }

    // ARBITRAGE_RECHECKED 는 실DB 통합으로 검증하지 않는다 — 검증 실행 생성이
    // REQUIRES_NEW 로 별도 커밋되어(ValidationRunCreateServiceImpl) 테스트 롤백으로 정리되지
    // 않고 uq_validation_run_active_manual_contract 잔존 충돌을 일으킨다.
    // 감사 호출 계약은 ArbitrageServiceTest.recordsArbitrageRecheckedAudit 가 검증한다.

    private long auditCount(String actionCode, String entityType, String entityId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM fgc.audit_log
                 WHERE action_code = ? AND entity_type = ? AND entity_id = ?
                """, Long.class, actionCode, entityType, entityId);
        return count == null ? 0 : count;
    }
}
