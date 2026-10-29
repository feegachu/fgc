package com.susukkang.fgc.assistant;

import com.susukkang.fgc.assistant.dto.PaymentCalculationRequest;
import com.susukkang.fgc.assistant.service.PaymentCalculationService;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest;
import com.susukkang.fgc.transaction.repository.CommissionPaymentQueryRepository;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** Real PostgreSQL, payment queries and precheck; the spy only coordinates a competing committed write. */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
class PaymentCalculationIntegrationTest {
    @Autowired PaymentCalculationService calculations;
    @Autowired CommissionPaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean CommissionPaymentQueryRepository queries;
    Long contractId;
    Long paymentId;

    @BeforeEach void createOwnedCommittedDraft() {
        AppUserView view = jdbc.queryForObject("SELECT user_id, login_id FROM fgc.app_user ORDER BY user_id LIMIT 1", (rs, row) -> {
            var value = new AppUserView();
            value.setUserId(rs.getLong("user_id")); value.setLoginId(rs.getString("login_id"));
            value.setPasswordHash("{bcrypt}test-only"); value.setUserName(value.getLoginId()); value.setRoleCode("SETTLEMENT");
            return value;
        });
        var principal = new FgcUserDetails(view, true, true);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        contractId = jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,
                    agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,
                    monthly_equivalent_first_premium,premium_conversion_rule_code,payment_cycle_code,
                    payment_term_months,standard_surrender_deduction_amount,current_status,data_origin)
                SELECT insurer_id,product_offering_id,?,DATE '2026-07-03',agent_id,organization_id,
                    premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,
                    premium_conversion_rule_code,payment_cycle_code,payment_term_months,
                    standard_surrender_deduction_amount,current_status,'MANUAL'
                FROM fgc.insurance_contract WHERE contract_id=1 RETURNING contract_id
                """, Long.class, "IT-733-" + UUID.randomUUID());
        Long item = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'", Long.class);
        Long policy = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='REG-CAP-GA-2026-V1' AND status='ACTIVE'", Long.class);
        var date = LocalDate.of(2026, 7, 31);
        paymentId = payments.create(new CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT", "IT-733-" + UUID.randomUUID(),
                contractId, 6L, item, new BigDecimal("100000"), date.withDayOfMonth(1), "PAYMENT", date,
                PaymentStage.GA_TO_FC, policy, List.of(new CommissionPaymentAttributionRequest(contractId, date,
                new BigDecimal("100000"), InclusionDecisionStatus.INCLUDED, ExclusionType.NONE,
                "IT-733 snapshot", "DIRECT", "IT-733-EVIDENCE", AttributionMethod.DIRECT)), "IT-733-EVIDENCE", "IT-733")).paymentId();
    }

    @AfterEach void cleanOwnedRows() {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (paymentId != null) {
                    jdbc.update("DELETE FROM fgc.transaction_attribution WHERE commission_transaction_id=?", paymentId);
                    jdbc.update("DELETE FROM fgc.commission_transaction WHERE commission_transaction_id=?", paymentId);
                }
                if (contractId != null) jdbc.update("DELETE FROM fgc.insurance_contract WHERE contract_id=?", contractId);
                // Append-only audit records are deliberately preserved; source rows belong only to this test.
            });
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void bothCalculationKindsUseRealReadOnlyPrecheckWithoutBusinessWrites() {
        var readOnlyStates = new ArrayList<String>();
        doAnswer(invocation -> {
            readOnlyStates.add(jdbc.queryForObject("SHOW transaction_read_only", String.class));
            return invocation.callRealMethod();
        }).when(queries).findById(paymentId);
        var countsBefore = counts();
        var paymentBefore = paymentRow();
        var attributionBefore = attributionRows();
        var balance = calculations.calculate(request(PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE));
        var candidate = calculations.calculate(request(PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE));
        assertThat(balance.amounts()).containsEntry("amount", "100000").containsEntry("attributedTotal", "100000").containsEntry("differenceAmount", "0");
        assertThat(candidate.capPreview()).isNotEmpty();
        assertThat(candidate.capPreview().getFirst()).containsEntry("candidateAmount", "100000");
        assertThat(candidate.snapshotOnly()).isTrue();
        assertThat(candidate.validity()).isNull();
        assertThat(readOnlyStates).isNotEmpty().containsOnly("on");
        assertThat(counts()).isEqualTo(countsBefore);
        assertThat(paymentRow()).isEqualTo(paymentBefore);
        assertThat(attributionRows()).isEqualTo(attributionBefore);
    }

    @Test @Timeout(45) void candidateAndBasisKeepOneSnapshotWhenAnotherTransactionCommitsAnEdit() throws Exception {
        var firstRead = new CountDownLatch(1);
        var writerDone = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var executor = Executors.newSingleThreadExecutor();
        doAnswer(invocation -> {
            var actualRow = invocation.callRealMethod();
            if (first.getAndSet(false)) {
                firstRead.countDown();
                assertThat(writerDone.await(15, TimeUnit.SECONDS)).as("competing write committed").isTrue();
            }
            return actualRow;
        }).when(queries).findById(paymentId);
        Future<?> writer = executor.submit(() -> {
            try {
                assertThat(firstRead.await(15, TimeUnit.SECONDS)).as("calculation established its snapshot").isTrue();
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    assertThat(jdbc.update("UPDATE fgc.commission_transaction SET amount=900000 WHERE commission_transaction_id=?", paymentId)).isEqualTo(1);
                    assertThat(jdbc.update("UPDATE fgc.transaction_attribution SET amount=900000 WHERE commission_transaction_id=?", paymentId)).isEqualTo(1);
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new AssertionError(error);
            } finally { writerDone.countDown(); }
        });
        try {
            var result = calculations.calculate(request(PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE));
            writer.get(20, TimeUnit.SECONDS);
            assertThat(result.basis().lines()).singleElement().satisfies(line -> assertThat(line.originalAmount()).isEqualTo("100000.00"));
            assertThat(result.capPreview()).isNotEmpty();
            assertThat(result.capPreview().getFirst()).containsEntry("candidateAmount", "100000");
            assertThat(jdbc.queryForObject("SELECT amount FROM fgc.commission_transaction WHERE commission_transaction_id=?", BigDecimal.class, paymentId)).isEqualByComparingTo("900000");
            assertThat(jdbc.queryForObject("SELECT amount FROM fgc.transaction_attribution WHERE commission_transaction_id=?", BigDecimal.class, paymentId)).isEqualByComparingTo("900000");
        } finally {
            firstRead.countDown(); writerDone.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private PaymentCalculationRequest request(PaymentCalculationRequest.Kind kind) {
        return new PaymentCalculationRequest("assistant.v1", kind, PaymentCalculationRequest.Mode.EXISTING,
                new PaymentCalculationRequest.Scope(paymentId.toString()), null, 0L);
    }
    private Map<String, Long> counts() {
        var result = new LinkedHashMap<String, Long>();
        for (var table : List.of("commission_transaction", "transaction_attribution", "cap_check", "cap_check_detail", "journal_header", "journal_line", "exception_case", "exception_occurrence", "audit_log"))
            result.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM fgc." + table, Long.class));
        return result;
    }
    private Map<String, Object> paymentRow() {
        return jdbc.queryForMap("SELECT * FROM fgc.commission_transaction WHERE commission_transaction_id=?", paymentId);
    }
    private List<Map<String, Object>> attributionRows() {
        return jdbc.queryForList("SELECT * FROM fgc.transaction_attribution WHERE commission_transaction_id=? ORDER BY transaction_attribution_id", paymentId);
    }
}
