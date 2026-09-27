package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.common.code.DataOrigin;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.common.code.SurrenderValueType;
import com.susukkang.fgc.common.util.DateUtil;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import static com.susukkang.fgc.common.code.ContractStatus.ACTIVE;
import static com.susukkang.fgc.common.code.ContractStatus.TERMINATED;
import static com.susukkang.fgc.common.code.PaymentCycleCode.MONTHLY;
import static com.susukkang.fgc.common.code.PremiumConversionRuleCode.MONTHLY_AS_IS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 보험계약 Repository 저장 및 데이터 매핑 통합 테스트
 * 실제 PostgreSQL에서 존재 여부, 조회, 등록 및 수정 SQL을 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-06
 */
@SpringBootTest
@Transactional
class ContractRepositoryIntegrationTest {

    @Autowired
    private InsuranceContractRepository insuranceContractRepository;

    @Autowired
    private ContractQueryRepository contractQueryRepository;

    @Autowired
    private ContractFinancialSnapshotRepository financialSnapshotRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("보험사·상품판매버전·설계사·조직의 연관관계를 확인한다")
    void checksReferenceExistence() {
        References refs = references();
        LocalDate contractDate = LocalDate.now(DateUtil.SEOUL_ZONE);

        assertThat(contractQueryRepository.existsActiveInsurer(refs.insurerId())).isTrue();
        assertThat(contractQueryRepository.existsValidProductOffering(
                refs.insurerId(), refs.productOfferingId(), contractDate)).isTrue();
        assertThat(contractQueryRepository.existsEligibleAgent(refs.agentId(), contractDate)).isTrue();
        assertThat(contractQueryRepository.existsValidAgentOrganization(
                refs.agentId(), refs.organizationId(), contractDate)).isTrue();
        assertThat(contractQueryRepository.existsValidProductOffering(
                Long.MAX_VALUE, refs.productOfferingId(), contractDate)).isFalse();
        assertThat(contractQueryRepository.existsValidProductOffering(
                refs.insurerId(), refs.productOfferingId(), LocalDate.of(1900, 1, 1))).isFalse();
        assertThat(contractQueryRepository.existsEligibleAgent(
                refs.agentId(), LocalDate.of(1900, 1, 1))).isFalse();

        Long managerId = jdbcTemplate.queryForObject("""
                SELECT agent_id
                  FROM fgc.agent
                 WHERE rank_code <> 'FC'
                   AND active_yn = TRUE
                   AND agent_status = 'ACTIVE'
                 ORDER BY agent_id
                 LIMIT 1
                """, Long.class);
        assertThat(contractQueryRepository.existsEligibleAgent(managerId, contractDate)).isFalse();
    }

    @Test
    @DisplayName("계약을 등록하고 ID와 계약번호로 다시 조회한다")
    void insertsAndSelectsContract() {
        References refs = references();
        InsuranceContract contract = newContract(refs);

        insuranceContractRepository.saveAndFlush(contract);
        entityManager.clear();
        assertThat(contract.getContractId()).isNotNull();
        assertThat(insuranceContractRepository.existsByInsurerIdAndContractNo(
                refs.insurerId(), contract.getContractNo())).isTrue();

        InsuranceContract selected = insuranceContractRepository.findById(contract.getContractId()).orElseThrow();
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
        insuranceContractRepository.saveAndFlush(contract);
        ContractSearchCondition condition = new ContractSearchCondition();
        condition.setContractNo(contract.getContractNo());

        Page<ContractView> page = contractQueryRepository.search(condition, PageRequest.of(0, 20));
        List<ContractView> result = page.getContent();
        long total = page.getTotalElements();

        assertThat(result).hasSize(1);
        assertThat(total).isEqualTo(1);
        assertThat(result.getFirst().getContractNo()).isEqualTo(contract.getContractNo());
        assertThat(result.getFirst().getAgentIdName()).startsWith("FC-");
        assertThat(result.getFirst().getContractStatus()).isEqualTo(ACTIVE);
        assertThat(result.getFirst().getCapResultStatus()).isNull();
        assertThat(result.getFirst().getDataOrigin()).isEqualTo(DataOrigin.MANUAL);
    }

    @Test
    @DisplayName("조직 ID 검색 조건으로 계약 목록과 건수를 거른다 (IF-API-11)")
    void selectsContractByOrgId() {
        // FGC-UI-CONT-W01 검색 조건 '조직' — FGC-FUN-018 화면의 기본 필터, 조직 필터 명세는 FGC-FUN-058(2차 통합검색)
        References refs = references();
        InsuranceContract contract = newContract(refs);
        insuranceContractRepository.saveAndFlush(contract);
        ContractSearchCondition condition = new ContractSearchCondition();
        condition.setContractNo(contract.getContractNo());
        condition.setOrgId(refs.organizationId());

        Page<ContractView> matched = contractQueryRepository.search(condition, PageRequest.of(0, 20));
        assertThat(matched.getContent()).hasSize(1);
        assertThat(matched.getTotalElements()).isEqualTo(1);

        // 존재하지 않는 조직으로 검색하면 걸러진다
        condition.setOrgId(-1L);
        Page<ContractView> unmatched = contractQueryRepository.search(condition, PageRequest.of(0, 20));
        assertThat(unmatched.getContent()).isEmpty();
        assertThat(unmatched.getTotalElements()).isZero();
        assertThat(contractQueryRepository.searchAll(condition)).isEmpty();
    }

    @Test
    @DisplayName("조건 객체와 검색값이 비어 있으면 목록·총건수·CSV를 조건 없이 조회한다")
    void supportsNullAndEmptySearchConditions() {
        InsuranceContract contract = insuranceContractRepository.saveAndFlush(newContract(references()));
        List<ContractView> expected = contractQueryRepository.searchAll(null);
        assertThat(expected).extracting(ContractView::getContractId).contains(contract.getContractId());

        ContractSearchCondition emptyContractNo = new ContractSearchCondition();
        emptyContractNo.setContractNo("");
        for (ContractSearchCondition condition : new ContractSearchCondition[] {
                null, new ContractSearchCondition(), emptyContractNo
        }) {
            Page<ContractView> page = contractQueryRepository.search(condition, PageRequest.of(0, 2));
            assertThat(page.getTotalElements()).isEqualTo(expected.size());
            assertThat(page.getContent()).extracting(ContractView::getContractId)
                    .containsExactlyElementsOf(expected.stream().limit(2).map(ContractView::getContractId).toList());
            assertThat(contractQueryRepository.searchAll(condition)).extracting(ContractView::getContractId)
                    .containsExactlyElementsOf(expected.stream().map(ContractView::getContractId).toList());
        }
    }

    @Test
    @DisplayName("SQL 구문처럼 보이는 계약번호도 검색값으로만 바인딩한다")
    void bindsSqlLikeContractNumberAsData() {
        References refs = references();
        String prefix = "IT-QUOTE-" + UUID.randomUUID();
        InsuranceContract quoted = insuranceContractRepository.saveAndFlush(
                newContract(refs, prefix + "' OR 1=1 --"));
        insuranceContractRepository.saveAndFlush(newContract(refs, prefix + "-OTHER"));
        ContractSearchCondition condition = new ContractSearchCondition();
        condition.setContractNo(quoted.getContractNo());

        Page<ContractView> page = contractQueryRepository.search(condition, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(ContractView::getContractId)
                .containsExactly(quoted.getContractId());
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(contractQueryRepository.searchAll(condition)).extracting(ContractView::getContractId)
                .containsExactly(quoted.getContractId());
    }

    @Test
    @DisplayName("계약번호 검색의 공백과 기존 LIKE 와일드카드 의미를 유지한다")
    void preservesWhitespaceAndLikeWildcards() {
        References refs = references();
        String prefix = "IT-SPACE-" + UUID.randomUUID();
        InsuranceContract plain = insuranceContractRepository.saveAndFlush(newContract(refs, prefix + "-1"));
        InsuranceContract spaced = insuranceContractRepository.saveAndFlush(newContract(refs, prefix + "-1 "));
        ContractSearchCondition condition = new ContractSearchCondition();
        condition.setContractNo(spaced.getContractNo());

        assertThat(contractQueryRepository.search(condition, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        assertThat(contractQueryRepository.searchAll(condition)).extracting(ContractView::getContractId)
                .containsExactly(spaced.getContractId());

        condition.setContractNo(prefix + "-_%");
        assertThat(contractQueryRepository.search(condition, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        assertThat(contractQueryRepository.searchAll(condition)).extracting(ContractView::getContractId)
                .containsExactly(spaced.getContractId(), plain.getContractId());
    }

    @Test
    @DisplayName("계약을 수정하면 변경 감지로 계약번호와 수정값이 반영된다")
    void updatesContractIncludingContractNumber() {
        References refs = references();
        InsuranceContract contract = newContract(refs);
        insuranceContractRepository.saveAndFlush(contract);
        String changedContractNo = "IT-UPDATED-" + UUID.randomUUID();
        contract.updateDetails(
                refs.insurerId(), refs.productOfferingId(), changedContractNo,
                LocalDate.now(DateUtil.SEOUL_ZONE), refs.agentId(), refs.organizationId(),
                new BigDecimal("120000"), new BigDecimal("120000"), new BigDecimal("120000"),
                MONTHLY_AS_IS, MONTHLY, 120, new BigDecimal("60000"), TERMINATED);
        insuranceContractRepository.flush();
        entityManager.clear();

        InsuranceContract selected = insuranceContractRepository.findById(contract.getContractId()).orElseThrow();
        assertThat(selected.getContractNo()).isEqualTo(changedContractNo);
        assertThat(selected.getCurrentStatus()).isEqualTo(TERMINATED);
        assertThat(selected.getPremiumPerCycleAmount()).isEqualByComparingTo("120000.00");

        ContractDetailResponse detail =
                contractQueryRepository.findDetailById(contract.getContractId()).orElseThrow();
        assertThat(detail.getContractNo()).isEqualTo(changedContractNo);
        assertThat(detail.getContractStatus()).isEqualTo(TERMINATED);
        assertThat(detail.getContractId()).isEqualTo(contract.getContractId());
        assertThat(detail.getInsurerId()).isEqualTo(refs.insurerId());
        assertThat(detail.getProductOfferingId()).isEqualTo(refs.productOfferingId());
        assertThat(detail.getAgentId()).isEqualTo(refs.agentId());
        assertThat(detail.getOrganizationId()).isEqualTo(refs.organizationId());
        assertThat(detail.getInsurerName()).isNotBlank();
        assertThat(detail.getProductName()).isNotBlank();
        assertThat(detail.getAgentName()).isNotBlank();
        assertThat(detail.getOrganizationName()).isNotBlank();
    }

    @Test
    @DisplayName("검색과 CSV는 동일한 조건을 적용하고 페이지와 전체 목록을 구분한다")
    void sharesSearchFiltersAndSortWithCsvExport() {
        References refs = references();
        String prefix = "IT-PAGE-" + UUID.randomUUID();
        InsuranceContract first = insuranceContractRepository.saveAndFlush(newContract(refs, prefix + "-1"));
        InsuranceContract second = insuranceContractRepository.saveAndFlush(newContract(refs, prefix + "-2"));
        ContractSearchCondition condition = new ContractSearchCondition();
        condition.setContractNo(prefix);
        condition.setInsurerId(refs.insurerId());
        condition.setProductOfferingId(refs.productOfferingId());
        condition.setAgentId(refs.agentId());
        condition.setOrgId(refs.organizationId());
        condition.setCurrentStatus(ACTIVE);
        condition.setContractDateFrom(first.getContractDate());
        condition.setContractDateTo(first.getContractDate());

        Page<ContractView> page = contractQueryRepository.search(condition, PageRequest.of(1, 1));

        assertThat(page.getContent()).extracting(ContractView::getContractId)
                .containsExactly(first.getContractId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(contractQueryRepository.searchAll(condition)).extracting(ContractView::getContractId)
                .containsExactly(second.getContractId(), first.getContractId());

        condition.setCurrentStatus(TERMINATED);
        assertThat(contractQueryRepository.search(condition, PageRequest.of(0, 1)).getTotalElements()).isZero();
        assertThat(contractQueryRepository.searchAll(condition)).isEmpty();
    }

    @Test
    @DisplayName("초회 재무 스냅샷은 한 번만 저장하고 계약일과 초회 보험료를 사용한다")
    void insertsInitialFinancialSnapshotIdempotently() {
        InsuranceContract contract = insuranceContractRepository.saveAndFlush(newContract(references()));
        Long contractId = contract.getContractId();

        assertThat(financialSnapshotRepository.insertInitialIfAbsent(contractId)).isEqualTo(1);
        assertThat(financialSnapshotRepository.insertInitialIfAbsent(contractId)).isZero();
        Long snapshotId = jdbcTemplate.queryForObject("""
                SELECT contract_financial_snapshot_id
                  FROM fgc.contract_financial_snapshot WHERE contract_id = ?
                """, Long.class, contractId);
        entityManager.clear();

        var snapshot = financialSnapshotRepository.findById(snapshotId).orElseThrow();
        assertThat(snapshot.getContractId()).isEqualTo(contractId);
        assertThat(snapshot.getAsOfDate()).isEqualTo(contract.getContractDate());
        assertThat(snapshot.getContractMonthNo()).isEqualTo(1);
        assertThat(snapshot.getCumulativePaidPremium()).isEqualByComparingTo(contract.getFirstPremiumAmount());
        assertThat(snapshot.getSurrenderValue()).isNull();
        assertThat(snapshot.getSurrenderValueType()).isEqualTo(SurrenderValueType.EXPECTED_TABLE);
        assertThat(snapshot.getRefundRateTableId()).isNull();
        assertThat(snapshot.getSourceRef()).isEqualTo("MANUAL_CONTRACT_INITIAL");
        assertThat(snapshot.getCreatedAt()).isNotNull();
    }

    private InsuranceContract newContract(References refs) {
        return newContract(refs, "IT-CONTRACT-" + UUID.randomUUID());
    }

    private InsuranceContract newContract(References refs, String contractNo) {
        return InsuranceContract.builder()
                .insurerId(refs.insurerId())
                .productOfferingId(refs.productOfferingId())
                .contractNo(contractNo)
                .contractDate(LocalDate.now(DateUtil.SEOUL_ZONE))
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
                    WHERE rank_code = 'FC'
                      AND active_yn = TRUE
                      AND agent_status = 'ACTIVE'
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
