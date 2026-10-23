package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.FeeRegimeDecisionInput;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설명 : 분급 체계 판정 입력 조회(#535)를 시드 계약으로 검증하는 PostgreSQL 통합 테스트
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FeeRegimeDecisionInputIntegrationTest {

    @Autowired
    private PolicyVersionRepository policyVersionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FeeRegimeDecisionInputService service() {
        return new FeeRegimeDecisionInputServiceImpl(policyVersionRepository);
    }

    private long contractId(String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?", Long.class, contractNo);
    }

    // 시나리오 1·4: G2(4년 판매버전) 값이 원본 그대로 반환되고, 판매종료일이 없으면 null이다
    @Test
    void returnsSeedG2ValuesAsStoredWithNullSalesEndDate() {
        long contractId = contractId("FGC-FGL02-202611-0001");

        FeeRegimeDecisionInput input = service().getByContractId(contractId);

        assertThat(input.contractId()).isEqualTo(contractId);
        assertThat(input.contractDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(input.offeringVersion()).isEqualTo("2027-FOUR-YEAR");
        assertThat(input.salesStartDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(input.salesEndDate()).isNull();
        assertThat(input.basicDocumentVersion()).isEqualTo("BD-2027-01");
        assertThat(input.basicDocumentDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(input.declaredFeeRegimeCode()).isEqualTo("FOUR_YEAR_2027");
    }

    // 시나리오 2: G1(현행 판매버전, 계약일 2027-03-02)은 선언 체계와 계약일이 그대로 반환된다(경계 판정은 #536)
    @Test
    void returnsSeedG1DeclaredRegimeAndContractDateWithoutJudging() {
        FeeRegimeDecisionInput input = service().getByContractId(contractId("FGC-FGL01-202703-0001"));

        assertThat(input.contractDate()).isEqualTo(LocalDate.of(2027, 3, 2));
        assertThat(input.offeringVersion()).isEqualTo("2026-CURRENT-A");
        assertThat(input.declaredFeeRegimeCode()).isEqualTo("CURRENT");
    }

    // 시나리오 3: 판매채널은 판정 입력에 없다 (SRC-036, 운영정책서 제45조)
    @Test
    void doesNotCarrySalesChannel() {
        assertThat(Arrays.stream(FeeRegimeDecisionInput.class.getRecordComponents())
                .map(RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase().contains("channel"));
    }

    // 시나리오 5: 없는 계약 ID는 기존 오류 코드 체계로 거절한다
    @Test
    void rejectsUnknownContractWithNotFound() {
        assertThatThrownBy(() -> service().getByContractId(-1L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test
    void rejectsNullContractId() {
        assertThatThrownBy(() -> service().getByContractId(null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
    }

    // 시나리오 6: 정의된 세 값 밖의 체계 코드(CHECK 제약 없음)도 바꾸지 않고 그대로 반환한다
    @Test
    void passesThroughUnknownFeeRegimeCodeUnchanged() {
        long contractId = contractId("FGC-FGL01-202703-0001");
        jdbcTemplate.update("""
                UPDATE fgc.product_offering SET fee_regime_code = 'TM_SPECIAL'
                 WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_id = ?)
                """, contractId);

        assertThat(service().getByContractId(contractId).declaredFeeRegimeCode()).isEqualTo("TM_SPECIAL");
    }
}
