package com.susukkang.fgc.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susukkang.fgc.bank.dto.BankLinkResponse;
import com.susukkang.fgc.bank.dto.BankReconciliationResponse;
import com.susukkang.fgc.bank.openbanking.BankAccount;
import com.susukkang.fgc.bank.openbanking.BankToken;
import com.susukkang.fgc.bank.openbanking.BankWithdrawal;
import com.susukkang.fgc.bank.openbanking.OpenBankingClient;
import com.susukkang.fgc.bank.openbanking.OpenBankingException;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/** FUN-090 지급 이체 대사: 은행 계좌 연결과 대사 실행을 실제 PostgreSQL 로 확인한다. 은행은 부르지 않는다. */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class BankReconciliationServiceIntegrationTest {

    /** 시드에 설계사 세 명의 확정 지급이 있는 달. */
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);
    private static final String FINTECH_USE_NUM = "199000000000000000000002";
    private static final BankToken TOKEN = new BankToken("ACCESS-1", "1100000001", 7_776_000L);

    @Autowired
    private BankLinkService linkService;
    @Autowired
    private BankReconciliationService reconciliationService;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private OpenBankingClient client;
    @Autowired
    private ApplicationEvents applicationEvents;

    @Test
    @DisplayName("은행 동의 뒤 조회에 동의한 첫 계좌를 연결하고 가린 번호만 감사로그에 남긴다")
    void linksTheFirstAccountThatAgreedToInquiry() {
        assertThat(linkService.status().linked()).isFalse();

        BankLinkResponse linked = link("settle01");

        assertThat(linked.linked()).isTrue();
        assertThat(linked.connected()).isTrue();
        assertThat(linked.bankName()).isEqualTo("모의은행");
        assertThat(linked.accountNumMasked()).isEqualTo("100-***-***002");
        assertThat(linked.accountHolderName()).isEqualTo("가상지에이");
        assertThat(linkService.status()).isEqualTo(linked);
        assertThat(jdbc.queryForObject("SELECT fintech_use_num FROM fgc.bank_account_link", String.class))
                .isEqualTo(FINTECH_USE_NUM);
        String audited = jdbc.queryForObject("""
                SELECT after_value::text FROM fgc.audit_log
                 WHERE action_code = 'BANK_ACCOUNT_LINKED' ORDER BY audit_log_id DESC LIMIT 1
                """, String.class);
        assertThat(audited).contains("100-***-***002").contains("모의은행")
                .doesNotContain(FINTECH_USE_NUM).doesNotContain("ACCESS-1");
    }

    @Test
    @DisplayName("모르는 요청이거나 남의 요청이거나 이미 쓴 요청이면 연결하지 않는다")
    void rejectsUnknownForeignAndReusedAuthorizations() {
        when(client.authorizeUrl(anyString())).thenAnswer(call -> "/bank?state=" + call.getArgument(0));
        when(client.exchange("CODE-1")).thenReturn(TOKEN);
        when(client.accounts(TOKEN)).thenReturn(accounts());
        String state = stateOf(linkService.authorizeUrl(userId("settle01")).authorizeUrl());

        assertBank(FgcErrorCode.BANK_004, () -> linkService.link(userId("settle01"), "CODE-1", "f".repeat(32)));
        assertBank(FgcErrorCode.BANK_004, () -> linkService.link(userId("admin"), "CODE-1", state));
        // 남이 시도한 요청은 그 자리에서 버린다. 원래 사용자도 다시 시작해야 한다.
        assertBank(FgcErrorCode.BANK_004, () -> linkService.link(userId("settle01"), "CODE-1", state));
        verify(client, never()).exchange(anyString());
        assertThat(linkService.status().linked()).isFalse();
    }

    @Test
    @DisplayName("조회에 동의한 계좌가 없으면 연결하지 않는다")
    void doesNotLinkWithoutAnInquiryAgreedAccount() {
        when(client.authorizeUrl(anyString())).thenAnswer(call -> "/bank?state=" + call.getArgument(0));
        when(client.exchange("CODE-1")).thenReturn(TOKEN);
        when(client.accounts(TOKEN)).thenReturn(List.of(
                new BankAccount("199000000000000000000001", "모의은행", "100-***-***001", "가상지에이", false)));
        String state = stateOf(linkService.authorizeUrl(userId("settle01")).authorizeUrl());

        assertBank(FgcErrorCode.BANK_005, () -> linkService.link(userId("settle01"), "CODE-1", state));
        assertThat(linkService.status().linked()).isFalse();
    }

    @Test
    @DisplayName("은행이 인가 코드를 거절하면 연결하지 않고 은행 오류로 알린다")
    void reportsBankFailuresWhileLinking() {
        when(client.authorizeUrl(anyString())).thenAnswer(call -> "/bank?state=" + call.getArgument(0));
        when(client.exchange("CODE-1")).thenThrow(new OpenBankingException("O0001"));
        String state = stateOf(linkService.authorizeUrl(userId("settle01")).authorizeUrl());

        assertBank(FgcErrorCode.BANK_003, () -> linkService.link(userId("settle01"), "CODE-1", state));
        assertThat(linkService.status().linked()).isFalse();
    }

    @Test
    @DisplayName("확정 지급 순액과 은행 출금을 참조코드로 맞춰 일치, 금액 다름, 이체 없음, 확인 안 된 출금으로 나눈다")
    void reconcilesConfirmedPaymentsWithBankWithdrawals() {
        Long runId = finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        List<Map<String, Object>> expected = expectedTransfers(MARCH);
        assertThat(expected).hasSizeGreaterThanOrEqualTo(3);
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        when(client.withdrawals(TOKEN, FINTECH_USE_NUM, today, today)).thenReturn(List.of(
                withdrawal(today, reference(MARCH, expected.get(0)), amount(expected.get(0))),
                withdrawal(today, reference(MARCH, expected.get(1)), amount(expected.get(1)).subtract(new BigDecimal("10000"))),
                withdrawal(today, "FGC2602-000001", new BigDecimal("300000")),
                withdrawal(today, "관리비", new BigDecimal("55000"))));

        BankReconciliationResponse result = reconciliationService.run(userId("settle01"), "2026-03");

        assertThat(result.settlementMonth()).isEqualTo(MARCH);
        assertThat(result.inquiryFrom()).isEqualTo(today);
        assertThat(result.inquiryTo()).isEqualTo(today);
        assertThat(result.bankName()).isEqualTo("모의은행");
        assertThat(result.accountNumMasked()).isEqualTo("100-***-***002");
        assertThat(result.withdrawalCount()).isEqualTo(4);
        assertThat(result.createdBy()).isEqualTo("settle01");
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.amountMismatchCount()).isEqualTo(1);
        assertThat(result.notTransferredCount()).isEqualTo(expected.size() - 2);
        assertThat(result.unknownWithdrawalCount()).isEqualTo(1);

        BankReconciliationResponse.Item mismatch = result.items().get(0);
        assertThat(mismatch.result()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(mismatch.agentCode()).isEqualTo(expected.get(1).get("agent_code"));
        assertThat(mismatch.agentName()).isEqualTo(expected.get(1).get("agent_name"));
        assertThat(mismatch.referenceCode()).isEqualTo(reference(MARCH, expected.get(1)));
        assertThat(mismatch.expectedAmount()).isEqualByComparingTo(amount(expected.get(1)));
        assertThat(mismatch.differenceAmount()).isEqualByComparingTo("-10000");
        assertThat(mismatch.bankTranDate()).isEqualTo(today);
        BankReconciliationResponse.Item notTransferred = result.items().get(1);
        assertThat(notTransferred.result()).isEqualTo("NOT_TRANSFERRED");
        assertThat(notTransferred.bankAmount()).isEqualByComparingTo("0");
        assertThat(notTransferred.differenceAmount()).isEqualByComparingTo(amount(expected.get(2)).negate());
        BankReconciliationResponse.Item unknown = result.items().get(result.items().size() - 2);
        assertThat(unknown.result()).isEqualTo("UNKNOWN_WITHDRAWAL");
        assertThat(unknown.agentCode()).isNull();
        assertThat(unknown.printContent()).isEqualTo("관리비");
        assertThat(unknown.bankAmount()).isEqualByComparingTo("55000");
        assertThat(result.items().get(result.items().size() - 1).result()).isEqualTo("MATCHED");

        assertThat(reconciliationService.latest("2026-03")).isEqualTo(result);
        assertThat(jdbc.queryForObject("""
                SELECT validation_run_id FROM fgc.bank_reconciliation_run WHERE bank_reconciliation_run_id = ?
                """, Long.class, result.bankReconciliationRunId())).isEqualTo(runId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fgc.audit_log
                 WHERE action_code = 'BANK_RECONCILIATION_RUN' AND entity_id = ?
                """, Integer.class, String.valueOf(result.bankReconciliationRunId()))).isEqualTo(1);
    }

    @Test
    @DisplayName("소수 금액은 지급 건마다 원 단위로 반올림한 뒤 더한다")
    void roundsEachPaymentBeforeSumming() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        Map<String, Object> first = expectedTransfers(MARCH).get(0);
        // 운영정책서 제17조의2: 상세행을 먼저 원 단위로 반올림하고 합계는 그 값을 더한다. 100.50 + 100.50 은 201 이 아니라 202 다.
        confirmedLine((Long) first.get("agent_id"), MARCH, "100.50", "PAYMENT");
        confirmedLine((Long) first.get("agent_id"), MARCH, "100.50", "PAYMENT");
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        when(client.withdrawals(TOKEN, FINTECH_USE_NUM, today, today)).thenReturn(List.of());

        BankReconciliationResponse result = reconciliationService.run(userId("settle01"), "2026-03");

        assertThat(result.items()).filteredOn(item -> reference(MARCH, first).equals(item.referenceCode()))
                .singleElement().satisfies(item -> assertThat(item.expectedAmount())
                        .isEqualByComparingTo(amount(first).add(new BigDecimal("202"))));
    }

    @Test
    @DisplayName("순액이 0원 이하인 설계사는 이체 대상이 아니고, 1원이라도 남으면 대상이다")
    void transfersOnlyPositiveNetAmounts() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        Map<String, Object> first = expectedTransfers(MARCH).get(0);
        Long agentId = (Long) first.get("agent_id");
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        when(client.withdrawals(TOKEN, FINTECH_USE_NUM, today, today)).thenReturn(List.of());

        // 1원이 남으면 이체 대상이다.
        confirmedDeduction(agentId, MARCH, amount(first).subtract(BigDecimal.ONE).toPlainString());
        assertThat(reconciliationService.run(userId("settle01"), "2026-03").items())
                .filteredOn(item -> reference(MARCH, first).equals(item.referenceCode()))
                .singleElement().satisfies(item -> {
                    assertThat(item.result()).isEqualTo("NOT_TRANSFERRED");
                    assertThat(item.expectedAmount()).isEqualByComparingTo("1");
                });

        // 0원이면 대상이 아니다.
        confirmedDeduction(agentId, MARCH, "1");
        assertThat(reconciliationService.run(userId("settle01"), "2026-03").items())
                .extracting(BankReconciliationResponse.Item::referenceCode).doesNotContain(reference(MARCH, first));

        // 차감이 더 많아 -1원이어도 대상이 아니다.
        confirmedDeduction(agentId, MARCH, "1");
        assertThat(reconciliationService.run(userId("settle01"), "2026-03").items())
                .extracting(BankReconciliationResponse.Item::referenceCode).doesNotContain(reference(MARCH, first));
    }

    @Test
    @DisplayName("결과 줄마다 서로 다른 번호를 준다")
    void numbersEveryResultLine() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        // 같은 날 같은 내용, 같은 금액의 출금 두 건. 화면이 줄을 구분하려면 내용이 아니라 번호가 있어야 한다.
        when(client.withdrawals(TOKEN, FINTECH_USE_NUM, today, today)).thenReturn(List.of(
                withdrawal(today, "관리비", new BigDecimal("55000")), withdrawal(today, "관리비", new BigDecimal("55000"))));

        BankReconciliationResponse result = reconciliationService.run(userId("settle01"), "2026-03");

        assertThat(result.items()).extracting(BankReconciliationResponse.Item::bankReconciliationItemId)
                .doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("확정 차감은 이체할 금액에서 뺀다")
    void subtractsConfirmedDeductionsFromTheExpectedTransfer() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        Map<String, Object> first = expectedTransfers(MARCH).get(0);
        confirmedDeduction((Long) first.get("agent_id"), MARCH, "20000");
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        when(client.withdrawals(TOKEN, FINTECH_USE_NUM, today, today)).thenReturn(List.of(
                withdrawal(today, reference(MARCH, first), amount(first).subtract(new BigDecimal("20000")))));

        BankReconciliationResponse result = reconciliationService.run(userId("settle01"), "2026-03");

        assertThat(result.items()).filteredOn(item -> reference(MARCH, first).equals(item.referenceCode()))
                .singleElement().satisfies(item -> {
                    assertThat(item.result()).isEqualTo("MATCHED");
                    assertThat(item.expectedAmount()).isEqualByComparingTo(amount(first).subtract(new BigDecimal("20000")));
                });
    }

    @Test
    @DisplayName("조회 기간은 확정일부터 31일까지이고 오늘을 넘지 않는다")
    void capsTheInquiryWindowAt31DaysAfterFinalization() {
        OffsetDateTime finalizedAt = DateUtil.nowSeoul().minusDays(60);
        finalizeMonth(MARCH, finalizedAt);
        link("settle01");
        LocalDate from = finalizedAt.toLocalDate();
        when(client.withdrawals(TOKEN, FINTECH_USE_NUM, from, from.plusDays(31))).thenReturn(List.of());

        BankReconciliationResponse result = reconciliationService.run(userId("settle01"), "2026-03");

        assertThat(result.inquiryFrom()).isEqualTo(from);
        assertThat(result.inquiryTo()).isEqualTo(from.plusDays(31));
        verify(client).withdrawals(TOKEN, FINTECH_USE_NUM, from, from.plusDays(31));
    }

    @Test
    @DisplayName("은행을 부르기 전에 그 달의 조회를 시작한다고 알린다")
    void announcesTheInquiryBeforeCallingTheBank() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        when(client.withdrawals(eq(TOKEN), eq(FINTECH_USE_NUM), any(), any())).thenAnswer(call -> {
            // 모의 은행은 이 알림을 받아 그 달의 이체를 준비한다. 조회보다 먼저여야 한다.
            assertThat(applicationEvents.stream(BankInquiryStarting.class)).containsExactly(new BankInquiryStarting(MARCH));
            return List.of();
        });

        reconciliationService.run(userId("settle01"), "2026-03");

        verify(client).withdrawals(eq(TOKEN), eq(FINTECH_USE_NUM), any(), any());
    }

    @Test
    @DisplayName("확정되지 않은 달은 대사하지 않고 은행도 부르지 않는다")
    void refusesAMonthThatIsNotFinalized() {
        link("settle01");

        assertBank(FgcErrorCode.BANK_002, () -> reconciliationService.run(userId("settle01"), "2026-03"));
        verify(client, never()).withdrawals(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("은행을 연결하지 않았으면 대사하지 않는다")
    void refusesWhenTheBankIsNotLinked() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());

        assertBank(FgcErrorCode.BANK_001, () -> reconciliationService.run(userId("settle01"), "2026-03"));
    }

    @Test
    @DisplayName("은행 조회가 실패하면 결과를 남기지 않는다")
    void storesNothingWhenTheBankFails() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        when(client.withdrawals(eq(TOKEN), eq(FINTECH_USE_NUM), any(), any())).thenThrow(new OpenBankingException("TIMEOUT"));

        assertBank(FgcErrorCode.BANK_003, () -> reconciliationService.run(userId("settle01"), "2026-03"));
        assertThat(reconciliationService.latest("2026-03")).isNull();
        assertThat(linkService.status().connected()).isTrue();
    }

    @Test
    @DisplayName("은행이 토큰을 거절하면 다시 연결하게 한다")
    void requiresRelinkingWhenTheBankRejectsTheToken() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        when(client.withdrawals(eq(TOKEN), eq(FINTECH_USE_NUM), any(), any())).thenThrow(new OpenBankingException("O0003"));

        assertBank(FgcErrorCode.BANK_001, () -> reconciliationService.run(userId("settle01"), "2026-03"));
        BankLinkResponse status = linkService.status();
        assertThat(status.linked()).isTrue();
        assertThat(status.connected()).isFalse();
    }

    @Test
    @DisplayName("이전 연결의 토큰이 거절돼도 그사이 새로 한 연결은 끊지 않는다")
    void keepsANewerConnectionWhenAnOlderTokenIsRejected() {
        finalizeMonth(MARCH, DateUtil.nowSeoul());
        link("settle01");
        BankToken newer = new BankToken("ACCESS-2", "1100000001", 7_776_000L);
        when(client.exchange("CODE-2")).thenReturn(newer);
        when(client.accounts(newer)).thenReturn(accounts());
        when(client.withdrawals(eq(TOKEN), eq(FINTECH_USE_NUM), any(), any())).thenAnswer(call -> {
            // 은행 응답을 기다리는 사이 다른 요청이 다시 연결했다.
            String state = stateOf(linkService.authorizeUrl(userId("settle01")).authorizeUrl());
            linkService.link(userId("settle01"), "CODE-2", state);
            throw new OpenBankingException("O0003");
        });

        assertBank(FgcErrorCode.BANK_001, () -> reconciliationService.run(userId("settle01"), "2026-03"));

        assertThat(linkService.status().connected()).isTrue();
    }

    @Test
    @DisplayName("대사한 적 없는 달은 결과가 없다")
    void hasNoResultBeforeTheFirstRun() {
        assertThat(reconciliationService.latest("2026-03")).isNull();
    }

    @Test
    @DisplayName("정산월 형식이 틀리면 입력 오류다")
    void rejectsAMalformedMonth() {
        assertBank(FgcErrorCode.COMMON_002, () -> reconciliationService.run(userId("settle01"), "2026-3"));
        assertBank(FgcErrorCode.COMMON_002, () -> reconciliationService.latest("202603"));
    }

    private BankLinkResponse link(String loginId) {
        when(client.authorizeUrl(anyString())).thenAnswer(call -> "/bank?state=" + call.getArgument(0));
        when(client.exchange("CODE-1")).thenReturn(TOKEN);
        when(client.accounts(TOKEN)).thenReturn(accounts());
        String state = stateOf(linkService.authorizeUrl(userId(loginId)).authorizeUrl());
        assertThat(state).matches("[0-9a-f]{32}");
        return linkService.link(userId(loginId), "CODE-1", state);
    }

    private static List<BankAccount> accounts() {
        return List.of(new BankAccount("199000000000000000000001", "모의은행", "100-***-***001", "가상지에이", false),
                new BankAccount(FINTECH_USE_NUM, "모의은행", "100-***-***002", "가상지에이", true));
    }

    private static String stateOf(String authorizeUrl) {
        return authorizeUrl.substring(authorizeUrl.indexOf("state=") + "state=".length());
    }

    private static void assertBank(FgcErrorCode expected, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FgcBusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private static BankWithdrawal withdrawal(LocalDate date, String printContent, BigDecimal amount) {
        return new BankWithdrawal(date, "100001", printContent, amount);
    }

    private static String reference(LocalDate month, Map<String, Object> transfer) {
        return "FGC%02d%02d-%06d".formatted(month.getYear() % 100, month.getMonthValue(), (Long) transfer.get("agent_id"));
    }

    private static BigDecimal amount(Map<String, Object> transfer) {
        return (BigDecimal) transfer.get("net");
    }

    /** 운영 쿼리와 따로 쓴 기대값: 설계사별 확정 지급 합계에서 확정 차감 합계를 뺀 금액. */
    private List<Map<String, Object>> expectedTransfers(LocalDate month) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> agent : jdbc.queryForList("""
                SELECT DISTINCT a.agent_id, a.agent_code, a.agent_name
                  FROM fgc.commission_transaction ct JOIN fgc.agent a ON a.agent_id = ct.recipient_agent_id
                 WHERE ct.payment_stage = 'GA_TO_FC' AND ct.status = 'CONFIRMED' AND ct.settlement_month = ?
                 ORDER BY a.agent_id
                """, month)) {
            BigDecimal paid = sum(month, (Long) agent.get("agent_id"), "PAYMENT");
            BigDecimal deducted = sum(month, (Long) agent.get("agent_id"), "DEDUCTION");
            Map<String, Object> row = new java.util.HashMap<>(agent);
            row.put("net", paid.subtract(deducted));
            rows.add(row);
        }
        return rows;
    }

    private BigDecimal sum(LocalDate month, Long agentId, String cashflowType) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM fgc.commission_transaction
                 WHERE payment_stage = 'GA_TO_FC' AND status = 'CONFIRMED' AND settlement_month = ?
                   AND recipient_agent_id = ? AND cashflow_type = ?
                """, BigDecimal.class, month, agentId, cashflowType);
    }

    /** 그 설계사의 확정 지급 1건을 본떠 확정 차감 1건을 만든다. */
    private void confirmedDeduction(Long agentId, LocalDate month, String amount) {
        confirmedLine(agentId, month, amount, "DEDUCTION");
    }

    /** 그 설계사의 확정 지급 1건을 본떠 확정 지급이나 확정 차감 1건을 더 만든다. */
    private void confirmedLine(Long agentId, LocalDate month, String amount, String cashflowType) {
        Long source = jdbc.queryForObject("""
                SELECT min(commission_transaction_id) FROM fgc.commission_transaction
                 WHERE payment_stage = 'GA_TO_FC' AND status = 'CONFIRMED' AND cashflow_type = 'PAYMENT'
                   AND settlement_month = ? AND recipient_agent_id = ?
                """, Long.class, month, agentId);
        Long deduction = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, settlement_month, due_date, amount, cashflow_type, status)
                SELECT payment_stage, 'ADJUSTMENT', ?, source_contract_id, recipient_agent_id, commission_item_id,
                       settlement_month, due_date, ?::numeric, ?, 'DRAFT'
                  FROM fgc.commission_transaction WHERE commission_transaction_id = ?
                RETURNING commission_transaction_id
                """, Long.class, "FUN090-LINE-" + ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), amount,
                cashflowType, source);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, attribution_seq, attribution_scope,
                    contract_id, agent_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, exclusion_type_snapshot, attribution_method, allocation_basis_snapshot, evidence_ref)
                SELECT ?, 1, attribution_scope, contract_id, agent_id, attribution_date, attribution_month, ?::numeric,
                       inclusion_status_snapshot, exclusion_type_snapshot, attribution_method, allocation_basis_snapshot, evidence_ref
                  FROM fgc.transaction_attribution
                 WHERE commission_transaction_id = ? ORDER BY attribution_seq LIMIT 1
                """, deduction, amount, source);
        jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", deduction);
    }

    /** 계산이 끝난 실행을 만들어 확정 상태로 올린다(상태 전이 가드가 허용하는 순서). */
    private Long finalizeMonth(LocalDate month, OffsetDateTime finalizedAt) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'MONTHLY') RETURNING validation_run_id
                """, Long.class, month, ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000));
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'RUNNING', current_step = 1, started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8, completed_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbc.update("""
                UPDATE fgc.validation_run SET status = 'FINALIZED', current_step = 10, finalized_at = ?, finalized_by = ?
                 WHERE validation_run_id = ?
                """, finalizedAt, userId("gaadmin"), runId);
        return runId;
    }

    private Long userId(String loginId) {
        return jdbc.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id = ?", Long.class, loginId);
    }
}
