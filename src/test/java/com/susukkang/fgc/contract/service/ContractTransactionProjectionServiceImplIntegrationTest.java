package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractTransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #108 이슈 To-do 테스트 — 지급 건 쪽. FGC-FGL01-202607-0001(contract_id=5)에 이미
 * 시드돼 있는 실제 CONFIRMED 지급 건 7건(각 건이 정확히 1개 귀속행으로 100% 귀속됨,
 * 확인 시점 기준)을 그대로 활용한다 — 이 프로젝션은 조회 전용이라 테스트가 데이터를
 * 새로 만들 필요가 없고, 만들지 않으니 정리(@AfterEach)도 필요 없다.
 */
@SpringBootTest
class ContractTransactionProjectionServiceImplIntegrationTest {

    @Autowired
    private ContractTransactionProjectionService contractTransactionProjectionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contractId(String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, contractNo);
    }

    @Test
    void unknownContractIdThrowsNotFound() {
        assertThatThrownBy(() -> contractTransactionProjectionService.findTransactionsByContractId(999_999_999L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test
    void contractWithNoAttributedTransactionsReturnsEmptyList() {
        Long emptyContractId = contractId("FGC-FGL01-202703-0001");

        List<ContractTransactionResponse> result =
                contractTransactionProjectionService.findTransactionsByContractId(emptyContractId);

        assertThat(result).isEmpty();
    }

    @Test
    void seededTransactionsAreGroupedWithFullyAttributedZeroDifferenceAndMaskedAgentNames() {
        Long contractId = contractId("FGC-FGL01-202607-0001");

        List<ContractTransactionResponse> result =
                contractTransactionProjectionService.findTransactionsByContractId(contractId);

        // 시드 데이터: commission_transaction 7건, 각각 attribution 1건씩 100% 귀속
        // (transaction_attribution.attributed_amount == commission_transaction.amount).
        assertThat(result).hasSize(7);
        assertThat(result).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo("CONFIRMED");
            assertThat(r.getStatusLabel()).isEqualTo("확정");
            assertThat(r.getAttributions()).hasSize(1);
            assertThat(r.getAttributionTotal()).isEqualByComparingTo(r.getAmount());
            assertThat(r.getDifferenceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });

        // 시드 설계사 이름(김정산·정팀장·한지사·서본부)이 원문 그대로 노출되면 안 된다 —
        // PersonalInfoMasker.maskName()을 거쳐야 한다("홍길동" 규칙과 같은 첫+*+끝 형태).
        assertThat(result).flatExtracting(ContractTransactionResponse::getAttributions)
                .extracting("agentName")
                .allSatisfy(name -> assertThat((String) name)
                        .doesNotContain("김정산", "정팀장", "한지사", "서본부"));
    }
}
