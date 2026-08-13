package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractReconciliationResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionTabResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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

        ContractTransactionTabResponse response =
                contractTransactionProjectionService.findTransactionsByContractId(emptyContractId);

        assertThat(response.transactions()).isEmpty();
        assertThat(response.reconciliations()).isEmpty();
    }

    @Test
    void seededTransactionsAreGroupedWithFullyAttributedZeroDifferenceAndUnmaskedAgentNames() {
        Long contractId = contractId("FGC-FGL01-202607-0001");

        List<ContractTransactionResponse> result =
                contractTransactionProjectionService.findTransactionsByContractId(contractId).transactions();

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

        // 설계사명은 마스킹하지 않는다(코드리뷰 반영 — 화면정의서에 설계사명 마스킹
        // 근거가 없고, 다른 화면(agents API·CONT-W03)은 원문을 그대로 노출한다).
        // 시드 이름 중 하나(김정산)가 원문 그대로 나오는지 확인.
        assertThat(result).flatExtracting(ContractTransactionResponse::getAttributions)
                .extracting("agentName")
                .contains("김정산");
    }

    @Test
    void transactionSplitAcrossMultipleContractsIsNotFalselyReportedAsUnderAttributed() {
        // 코드리뷰 반영 — commission_transaction_id=37(amount=210,000)은 두 계약에
        // 105,000원씩 정확히 100% 귀속돼 있다(정착지원금·공통비류 분할 귀속 시나리오와
        // 같은 모양). 이 계약(FGC-FGL01-202607-0005) 하나만 놓고 보면 attributionTotal은
        // 105,000이라 amount(210,000)와 다르지만, 실제로는 다른 계약 몫까지 합치면
        // 정확히 귀속된 상태다 — differenceAmount는 "이 계약 몫"이 아니라 "지급 건
        // 전체 귀속 합계" 기준으로 계산해야 0이 나와야 한다.
        Long contractId = contractId("FGC-FGL01-202607-0005");

        List<ContractTransactionResponse> result =
                contractTransactionProjectionService.findTransactionsByContractId(contractId).transactions();

        ContractTransactionResponse splitTransaction = result.stream()
                .filter(r -> r.getCommissionTransactionId() == 37L)
                .findFirst()
                .orElseThrow();

        assertThat(splitTransaction.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(210_000));
        // 이 계약 몫만 담은 attributions/attributionTotal은 여전히 105,000이어야 한다
        assertThat(splitTransaction.getAttributions()).hasSize(1);
        assertThat(splitTransaction.getAttributionTotal()).isEqualByComparingTo(BigDecimal.valueOf(105_000));
        // 하지만 차액은 지급 건 전체 기준으로 0이어야 한다 — 고치기 전 코드였다면
        // 210,000 - 105,000 = 105,000으로 잘못 나왔을 것이다.
        assertThat(splitTransaction.getDifferenceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // 시드 데이터에는 reconciliation_result가 하나도 없어서, 여기서만 CREATED 상태의
    // reconciliation_run + reconciliation_result 1건을 만들어 확인한다. 클래스 전체가
    // @Transactional이 아니라 이 테스트에만 걸어서 커밋 없이 롤백으로 정리한다
    // (FinalizedValidationRunImmutabilityIntegrationTest와 같은 패턴).
    @Test
    @Transactional
    void reconciliationResultsForContractAreIncludedInResponse() {
        Long contractId = contractId("FGC-FGL01-202607-0001");

        Long runId = jdbcTemplate.queryForObject(
                """
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, status)
                VALUES ('2026-07-01', 'GA_TO_FC', 'CREATED')
                RETURNING reconciliation_run_id
                """, Long.class);

        jdbcTemplate.update(
                """
                INSERT INTO fgc.reconciliation_result
                    (reconciliation_run_id, match_group_key, contract_id, result_type,
                     expected_total_amount, actual_total_amount, difference_amount, primary_reason_code)
                VALUES (?, 'test-match-group', ?, 'AMOUNT_DIFFERENCE', 100000, 90000, 10000, 'TEST_REASON')
                """, runId, contractId);

        ContractTransactionTabResponse response =
                contractTransactionProjectionService.findTransactionsByContractId(contractId);

        assertThat(response.reconciliations()).hasSize(1);
        ContractReconciliationResponse reconciliation = response.reconciliations().get(0);
        assertThat(reconciliation.resultType().name()).isEqualTo("AMOUNT_DIFFERENCE");
        assertThat(reconciliation.resultTypeLabel()).isEqualTo("금액 차이");
        assertThat(reconciliation.differenceAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));
    }
}
