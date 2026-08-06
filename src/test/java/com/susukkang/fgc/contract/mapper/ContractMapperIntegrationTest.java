package com.susukkang.fgc.contract.mapper;

import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.susukkang.fgc.contract.domain.ContractStatus.ACTIVE;
import static com.susukkang.fgc.contract.domain.ContractStatus.TERMINATED;
import static com.susukkang.fgc.contract.domain.PaymentCycleCode.MONTHLY;
import static com.susukkang.fgc.contract.domain.PremiumConversionRuleCode.MONTHLY_AS_IS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 보험계약 Mapper SQL 및 데이터 매핑 통합 테스트
 * 실제 PostgreSQL에서 존재 여부, 조회, 등록 및 수정 SQL을 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-06
 */
@SpringBootTest
@Transactional
class ContractMapperIntegrationTest {

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("보험사·상품판매버전·설계사·조직의 연관관계를 확인한다")
    void checksReferenceExistence() {
        References refs = references();
        LocalDate contractDate = LocalDate.now();

        assertThat(contractMapper.existsInsurer(refs.insurerId())).isTrue();
        assertThat(contractMapper.existsProductOffering(
                refs.insurerId(), refs.productOfferingId(), contractDate)).isTrue();
        assertThat(contractMapper.existsAgent(refs.agentId(), contractDate)).isTrue();
        assertThat(contractMapper.existsAgentOrganization(
                refs.agentId(), refs.organizationId(), contractDate)).isTrue();
        assertThat(contractMapper.existsProductOffering(
                Long.MAX_VALUE, refs.productOfferingId(), contractDate)).isFalse();
        assertThat(contractMapper.existsProductOffering(
                refs.insurerId(), refs.productOfferingId(), LocalDate.of(1900, 1, 1))).isFalse();
        assertThat(contractMapper.existsAgent(
                refs.agentId(), LocalDate.of(1900, 1, 1))).isFalse();
    }

    @Test
    @DisplayName("계약을 등록하고 ID와 계약번호로 다시 조회한다")
    void insertsAndSelectsContract() {
        References refs = references();
        InsuranceContract contract = newContract(refs);

        assertThat(contractMapper.insertContract(contract)).isEqualTo(1);
        assertThat(contract.getContractId()).isNotNull();
        assertThat(contractMapper.existsContractNo(
                refs.insurerId(), contract.getContractNo())).isTrue();

        InsuranceContract selected = contractMapper.selectById(contract.getContractId());
        assertThat(selected.getContractId()).isEqualTo(contract.getContractId());
        assertThat(selected.getContractNo()).isEqualTo(contract.getContractNo());
        assertThat(selected.getMonthlyEquivalentFirstPremium())
                .isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("검색 조건으로 등록한 계약 목록을 조회한다")
    void selectsContractByCondition() {
        References refs = references();
        InsuranceContract contract = newContract(refs);
        contractMapper.insertContract(contract);
        ContractSearchCondition condition = new ContractSearchCondition();
        condition.setContractNo(contract.getContractNo());

        List<ContractView> result = contractMapper.selectByCondition(condition);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getContractNo()).isEqualTo(contract.getContractNo());
        assertThat(result.getFirst().getAgentIdName()).startsWith("FC-");
    }

    @Test
    @DisplayName("계약을 수정하면 계약번호는 유지되고 수정값이 반영된다")
    void updatesContractWithoutChangingContractNumber() {
        References refs = references();
        InsuranceContract contract = newContract(refs);
        contractMapper.insertContract(contract);
        InsuranceContract updated = InsuranceContract.builder()
                .contractId(contract.getContractId())
                .insurerId(refs.insurerId())
                .productOfferingId(refs.productOfferingId())
                .contractNo(contract.getContractNo())
                .contractDate(LocalDate.now())
                .agentId(refs.agentId())
                .organizationId(refs.organizationId())
                .premiumPerCycleAmount(new BigDecimal("120000"))
                .firstPremiumAmount(new BigDecimal("120000"))
                .monthlyEquivalentFirstPremium(new BigDecimal("120000"))
                .premiumConversionRuleCode(MONTHLY_AS_IS)
                .paymentCycleCode(MONTHLY)
                .paymentTermMonths(120)
                .standardSurrenderDeductionAmount(new BigDecimal("60000"))
                .currentStatus(TERMINATED)
                .dataOrigin(DataOrigin.MANUAL)
                .build();

        assertThat(contractMapper.updateContract(updated)).isEqualTo(1);

        InsuranceContract selected = contractMapper.selectById(contract.getContractId());
        assertThat(selected.getContractNo()).isEqualTo(contract.getContractNo());
        assertThat(selected.getCurrentStatus()).isEqualTo(TERMINATED);
        assertThat(selected.getPremiumPerCycleAmount()).isEqualByComparingTo("120000.00");

        ContractDetailResponse detail =
                contractMapper.selectContractDetailById(contract.getContractId());
        assertThat(detail.getContractNo()).isEqualTo(contract.getContractNo());
        assertThat(detail.getContractStatus()).isEqualTo(TERMINATED);
    }

    private InsuranceContract newContract(References refs) {
        return InsuranceContract.builder()
                .insurerId(refs.insurerId())
                .productOfferingId(refs.productOfferingId())
                .contractNo("IT-CONTRACT-" + UUID.randomUUID())
                .contractDate(LocalDate.now())
                .agentId(refs.agentId())
                .organizationId(refs.organizationId())
                .premiumPerCycleAmount(new BigDecimal("100000"))
                .firstPremiumAmount(new BigDecimal("100000"))
                .monthlyEquivalentFirstPremium(new BigDecimal("100000"))
                .premiumConversionRuleCode(MONTHLY_AS_IS)
                .paymentCycleCode(MONTHLY)
                .paymentTermMonths(120)
                .standardSurrenderDeductionAmount(new BigDecimal("50000"))
                .currentStatus(ACTIVE)
                .dataOrigin(DataOrigin.MANUAL)
                .build();
    }

    private References references() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT p.insurer_id,
                       po.product_offering_id,
                       a.agent_id,
                       a.organization_id
                FROM fgc.product p
                JOIN fgc.product_offering po ON po.product_id = p.product_id
                CROSS JOIN LATERAL (
                    SELECT agent_id, organization_id
                    FROM fgc.agent
                    ORDER BY agent_id
                    LIMIT 1
                ) a
                ORDER BY po.product_offering_id
                LIMIT 1
                """);
        return new References(
                ((Number) row.get("insurer_id")).longValue(),
                ((Number) row.get("product_offering_id")).longValue(),
                ((Number) row.get("agent_id")).longValue(),
                ((Number) row.get("organization_id")).longValue()
        );
    }

    private record References(
            Long insurerId,
            Long productOfferingId,
            Long agentId,
            Long organizationId
    ) {
    }
}
