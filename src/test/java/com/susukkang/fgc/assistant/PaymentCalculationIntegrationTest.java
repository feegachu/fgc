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
    @Autowired com.susukkang.fgc.assistant.service.PicoPaymentContextService picoContext;
    @Autowired CommissionPaymentService payments;
    @Autowired com.susukkang.fgc.assistant.service.PicoGuideStartService guideStarts;
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
                    assertThat(jdbc.update("UPDATE fgc.transaction_attribution SET attributed_amount=900000 WHERE commission_transaction_id=?", paymentId)).isEqualTo(1);
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
            assertThat(jdbc.queryForObject("SELECT attributed_amount FROM fgc.transaction_attribution WHERE commission_transaction_id=?", BigDecimal.class, paymentId)).isEqualByComparingTo("900000");
        } finally {
            firstRead.countDown(); writerDone.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void missingPaymentPreservesTheExistingPaymentServiceError() {
        var request = new PaymentCalculationRequest("assistant.v1", PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE,
                PaymentCalculationRequest.Mode.EXISTING, new PaymentCalculationRequest.Scope(Long.toString(Long.MAX_VALUE)), null, 0L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> calculations.calculate(request))
                .isInstanceOfSatisfying(com.susukkang.fgc.common.exception.FgcBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(com.susukkang.fgc.common.exception.FgcErrorCode.COMMON_002));
    }

    @Test void picoContextReadsAnAuthorizedSnapshotWithoutWritesAndDeniesAgentBeforeQuery() {
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
            return invocation.callRealMethod();
        }).when(queries).findById(paymentId);
        var before = counts();
        var source = paymentRow();
        assertThat(picoContext.read(paymentId).status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(counts()).isEqualTo(before); assertThat(paymentRow()).isEqualTo(source);
        org.mockito.Mockito.clearInvocations(queries);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent", "unused",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGENT"))));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> picoContext.read(paymentId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(queries);
    }

    @Test void picoMissingPaymentMapsRealServiceValidationTo404WithoutChangingExistingApi() {
        long missingId = Long.MAX_VALUE;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.commission_transaction WHERE commission_transaction_id=?", Long.class, missingId)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> payments.get(missingId))
                .isInstanceOfSatisfying(com.susukkang.fgc.common.exception.FgcBusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(com.susukkang.fgc.common.exception.FgcErrorCode.COMMON_002);
                    assertThat(error.getField()).isEqualTo("paymentId");
                });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> picoContext.read(missingId))
                .isInstanceOfSatisfying(com.susukkang.fgc.common.exception.FgcBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(com.susukkang.fgc.common.exception.FgcErrorCode.COMMON_004));
    }

    @Test @Timeout(45) void guideStartIsReadOnlyAndRejectsASeparatelyCommittedPaymentEdit() throws Exception {
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
            return invocation.callRealMethod();
        }).when(queries).findById(paymentId);
        var before = counts(); var source = paymentRow(); var rows = attributionRows();
        var request = new com.susukkang.fgc.assistant.dto.PicoGuideRequest("payment.draft","1.0",paymentId.toString(),"r1",null);
        var prepared = guideStarts.prepare(request,"Bearer integration-session");
        var started = guideStarts.start(new com.susukkang.fgc.assistant.dto.PicoGuideRequest(request.workflowId(),request.guideVersion(),request.paymentId(),"r1",prepared.prepareToken()),"Bearer integration-session");
        assertThat(started.status()).isEqualTo("STARTED");
        assertThat(counts()).isEqualTo(before);assertThat(paymentRow()).isEqualTo(source);assertThat(attributionRows()).isEqualTo(rows);
        var outdated = guideStarts.prepare(request,"Bearer integration-session");
        var writer = Executors.newSingleThreadExecutor();
        try {
            writer.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(jdbc.update("UPDATE fgc.commission_transaction SET amount=900000 WHERE commission_transaction_id=?",paymentId)).isEqualTo(1);
                assertThat(jdbc.update("UPDATE fgc.transaction_attribution SET attributed_amount=900000 WHERE commission_transaction_id=?",paymentId)).isEqualTo(1);
            })).get(15,TimeUnit.SECONDS);
            var committed = paymentRow();var committedRows=attributionRows();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> guideStarts.start(new com.susukkang.fgc.assistant.dto.PicoGuideRequest(request.workflowId(),request.guideVersion(),request.paymentId(),"r1",outdated.prepareToken()),"Bearer integration-session"))
                    .isInstanceOfSatisfying(com.susukkang.fgc.common.exception.FgcBusinessException.class,error -> assertThat(error.getErrorCode()).isEqualTo(com.susukkang.fgc.common.exception.FgcErrorCode.IMPL_GUIDE_STATE_STALE));
            assertThat(paymentRow()).isEqualTo(committed);assertThat(attributionRows()).isEqualTo(committedRows);assertThat(counts()).isEqualTo(before);
        } finally {writer.shutdownNow();assertThat(writer.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @Autowired com.fasterxml.jackson.databind.ObjectMapper candidateMapper;
    private PaymentCalculationRequest candidateRequest(String operation,String amount) {
        var source=payments.get(paymentId);
        var candidate=candidateMapper.createObjectNode().put("operation",operation);
        var payment=candidate.putObject("payment");payment.put("sourceType",source.sourceType());payment.put("sourceBusinessKey",source.sourceBusinessKey());payment.put("contractId",contractId.toString());payment.put("agentId",source.agentId().toString());payment.put("commissionItemId",source.commissionItemId().toString());payment.put("amount",amount);payment.put("settlementMonth","2026-07");payment.put("cashflowType",source.cashflowType());payment.put("scheduledPaymentDate",source.scheduledPaymentDate().toString());payment.put("paymentStage",source.paymentStage().name());payment.put("allocationPolicyVersion",source.allocationPolicyVersion().toString());payment.put("evidenceRef",source.evidenceRef());payment.put("note",source.note());
        var row=payment.putArray("attributions").addObject();row.put("contractId",contractId.toString());row.put("attributionDate","2026-07-31");row.put("amount",amount);row.put("inclusionDecisionStatus","INCLUDED");row.put("exclusionType","NONE");row.put("inclusionDecisionReason","IT-733 snapshot");row.put("allocationBasis","DIRECT");row.put("evidenceRef","IT-733-EVIDENCE");row.put("attributionMethod","DIRECT");
        return new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE,PaymentCalculationRequest.Mode.HYPOTHETICAL,operation.equals("REPLACE_DRAFT")?new PaymentCalculationRequest.Scope(paymentId.toString()):null,candidate,5L);
    }
    @Test void hypotheticalReplacementUsesRealGateAndDoesNotChangeAnySource() {
        var before=counts();var source=paymentRow();var rows=attributionRows();var expected=payments.precheck(paymentId);
        var same=calculations.calculate(candidateRequest("REPLACE_DRAFT","100000"));
        assertThat(same.confirmable()).isEqualTo(expected.confirmable());assertThat(same.blockers().stream().map(com.susukkang.fgc.assistant.dto.PaymentCalculationSnapshot.Reason::code).toList()).containsExactlyElementsOf(expected.blockers().stream().map(com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse.Blocker::code).toList());
        var changed=calculations.calculate(candidateRequest("REPLACE_DRAFT","120000"));
        assertThat(changed.evidenceKind()).isEqualTo("HYPOTHETICAL");assertThat(changed.validity()).isNull();assertThat(changed.recommendation().allowed()).isFalse();
        assertThat(changed.capPreview()).anySatisfy(row->assertThat(row).containsEntry("candidateAmount", "120000"));
        assertThat(paymentRow()).isEqualTo(source);assertThat(attributionRows()).isEqualTo(rows);assertThat(counts()).isEqualTo(before);
    }
    @Test void hypotheticalNewCandidateIsNotAddedTwiceOrPersisted() {
        var before=counts();var source=paymentRow();var rows=attributionRows();
        var result=calculations.calculate(candidateRequest("NEW_PAYMENT","100000"));
        assertThat(result.evidenceKind()).isEqualTo("HYPOTHETICAL");assertThat(result.resolvedScope()).isEmpty();
        assertThat(result.capPreview()).anySatisfy(row->assertThat(row).containsEntry("candidateAmount", "100000"));
        assertThat(counts()).isEqualTo(before);assertThat(paymentRow()).isEqualTo(source);assertThat(attributionRows()).isEqualTo(rows);
    }
    @Test void newCandidateIncludesPendingStatusJobsWithoutPersistingItself() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            long event = candidateStatusEvent(contractId, 1, "LAPSED");
            jdbc.update("INSERT INTO fgc.contract_event_job(contract_status_event_id,processing_job) VALUES (?, 'PaymentHoldJob')", event);
            var before = counts();
            assertHeldCandidate(calculations.calculate(candidateRequest("NEW_PAYMENT", "100000")));
            assertThat(counts()).isEqualTo(before);
            tx.setRollbackOnly();
        });
    }

    @Test void replacementChecksCandidateContractInsteadOfOriginalDraftContract() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            long other = copyCandidateContract();
            long event = candidateStatusEvent(other, 1, "LAPSED");
            jdbc.update("INSERT INTO fgc.contract_event_job(contract_status_event_id,processing_job) VALUES (?, 'PaymentHoldJob')", event);
            assertThat(payments.precheck(paymentId).confirmable()).isTrue();
            var before = counts();
            assertHeldCandidate(calculations.calculate(candidateForContract("REPLACE_DRAFT", other)));
            assertThat(counts()).isEqualTo(before);
            tx.setRollbackOnly();
        });
    }

    @Test void newCandidateUsesCurrentStatusPolicyEvenWithoutPendingJobs() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            candidateStatusEvent(contractId, 1, "LAPSED");
            var before = counts();
            assertHeldCandidate(calculations.calculate(candidateRequest("NEW_PAYMENT", "100000")));
            assertThat(counts()).isEqualTo(before);
            tx.setRollbackOnly();
        });
    }

    @Test void replacementDropsOldContractJobsButPreservesOriginalOpenHold() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            long other = copyCandidateContract();
            long event = candidateStatusEvent(contractId, 1, "LAPSED");
            jdbc.update("INSERT INTO fgc.contract_event_job(contract_status_event_id,processing_job) VALUES (?, 'PaymentHoldJob')", event);
            var moved = candidateForContract("REPLACE_DRAFT", other);
            assertThat(calculations.calculate(moved).confirmable()).isTrue();
            jdbc.update("INSERT INTO fgc.payment_hold(commission_transaction_id,contract_status_event_id,reason_code) VALUES (?,?,'STATUS_INACTIVE')", paymentId, event);
            var before = counts();
            assertHeldCandidate(calculations.calculate(moved));
            assertThat(counts()).isEqualTo(before);
            tx.setRollbackOnly();
        });
    }

    private void assertHeldCandidate(com.susukkang.fgc.assistant.dto.PaymentCalculationSnapshot result) {
        assertThat(result.confirmable()).isFalse();
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.blockers()).anySatisfy(blocker -> assertThat(blocker.code()).isEqualTo("FGC-IMPL-PAYMENT_HELD"));
    }

    private PaymentCalculationRequest candidateForContract(String operation, long targetContract) {
        var request = candidateRequest(operation, "100000");
        var payment = (com.fasterxml.jackson.databind.node.ObjectNode) request.candidate().get("payment");
        payment.put("contractId", Long.toString(targetContract));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payment.get("attributions").get(0)).put("contractId", Long.toString(targetContract));
        return request;
    }

    private long candidateStatusEvent(long targetContract, int sequence, String status) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,received_at,
                    source_system,source_event_key,data_origin)
                VALUES (?,?,?,TIMESTAMPTZ '2026-07-15 00:00:00+09',TIMESTAMPTZ '2026-07-15 00:00:00+09',
                    'IT-841',?,'MANUAL') RETURNING contract_status_event_id
                """, Long.class, targetContract, sequence, status, UUID.randomUUID().toString());
    }

    private long copyCandidateContract() {
        return jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,
                    agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,
                    monthly_equivalent_first_premium,premium_conversion_rule_code,payment_cycle_code,
                    payment_term_months,standard_surrender_deduction_amount,current_status,data_origin)
                SELECT insurer_id,product_offering_id,?,contract_date,agent_id,organization_id,
                    premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,
                    premium_conversion_rule_code,payment_cycle_code,payment_term_months,
                    standard_surrender_deduction_amount,current_status,'MANUAL'
                FROM fgc.insurance_contract WHERE contract_id=? RETURNING contract_id
                """, Long.class, "IT-841-" + UUID.randomUUID(), contractId);
    }

    @Test void hypotheticalCannotReplaceNonDraftPayment() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE fgc.commission_transaction SET status='CANCELLED' WHERE commission_transaction_id=?",paymentId);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->calculations.calculate(candidateRequest("REPLACE_DRAFT","100000"))).isInstanceOf(com.susukkang.fgc.common.exception.FgcBusinessException.class);
            status.setRollbackOnly();
        });
    }

    @Test void hypotheticalCandidateIncludesOtherConfirmedPaymentsExactlyOnce() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            try {
                var original=payments.get(paymentId);var date=LocalDate.of(2026,7,31);
                var actual=payments.create(new CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT","IT-733-CONFIRMED-"+UUID.randomUUID(),contractId,6L,original.commissionItemId(),new BigDecimal("40000"),date.withDayOfMonth(1),"PAYMENT",date,PaymentStage.GA_TO_FC,original.allocationPolicyVersion(),List.of(new CommissionPaymentAttributionRequest(contractId,date,new BigDecimal("40000"),InclusionDecisionStatus.INCLUDED,ExclusionType.NONE,"IT-confirmed","DIRECT","IT-EVIDENCE",AttributionMethod.DIRECT)),"IT-EVIDENCE","IT"));
                payments.confirm(actual.paymentId(),"IT-733-"+UUID.randomUUID());
                var before=counts();var real=payments.precheck(paymentId);
                var replacement=calculations.calculate(candidateRequest("REPLACE_DRAFT","100000"));
                var fresh=calculations.calculate(candidateRequest("NEW_PAYMENT","100000"));
                assertThat(real.capPreview()).anySatisfy(row->assertThat(row.existingIncludedAmount()).isEqualTo(40000L));
                for(var candidate:List.of(replacement,fresh)) {
                    assertThat(candidate.capPreview()).anySatisfy(row->assertThat(row).containsEntry("existingIncludedAmount","40000").containsEntry("candidateAmount","100000"));
                    assertThat(candidate.confirmable()).isEqualTo(real.confirmable());
                }
                assertThat(counts()).isEqualTo(before);
            } finally {transaction.setRollbackOnly();}
        });
    }

    @Test void candidateQueryPlansInlineBothSourcesInsteadOfMaterializingWholeTables() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var candidate=new com.susukkang.fgc.transaction.domain.PaymentCandidateProjection(paymentId,paymentRow(),attributionRows());
        var parameters=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("candidatePaymentId",paymentId).addValue("candidatePayment",candidate.paymentJson())
                .addValue("candidateAttributions",candidate.attributionJson()).addValue("paymentId",paymentId)
                .addValue("transactionAttributionId",jdbc.queryForObject("SELECT transaction_attribution_id FROM fgc.transaction_attribution WHERE commission_transaction_id=?",Long.class,paymentId));
        var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        Class<?> sqlType=Class.forName("com.susukkang.fgc.transaction.repository.CommissionPaymentSql");
        for(String name:List.of("FIND_CONFIRMATION_DATA","FIND_CAP_RULE_SNAPSHOT")) {
            String original=(String)org.springframework.test.util.ReflectionTestUtils.getField(sqlType,name);
            String projected=candidate.sql(original);
            var before=mapper.readTree(named.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+projected.replace("AS NOT MATERIALIZED (","AS MATERIALIZED ("),parameters,String.class));
            var after=mapper.readTree(named.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+projected,parameters,String.class));
            assertThat(before.findValuesAsText("Node Type")).contains("CTE Scan");
            assertThat(after.findValuesAsText("Node Type")).doesNotContain("CTE Scan");
            System.out.println("#848 "+name+" materialized(ms)="+before.get(0).get("Execution Time")+", inlined(ms)="+after.get(0).get("Execution Time")+", node types="+after.findValuesAsText("Node Type")+", index predicates="+after.findValuesAsText("Index Cond"));
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
    private String paymentRow() {
        // Canonical PostgreSQL JSON compares all values, including JDBC array columns by content.
        return jdbc.queryForObject("SELECT to_jsonb(t)::text FROM fgc.commission_transaction t WHERE commission_transaction_id=?", String.class, paymentId);
    }
    private String attributionRows() {
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY transaction_attribution_id), '[]'::jsonb)::text FROM fgc.transaction_attribution t WHERE commission_transaction_id=?", String.class, paymentId);
    }
}
