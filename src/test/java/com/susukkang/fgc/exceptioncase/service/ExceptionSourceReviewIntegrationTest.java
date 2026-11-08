package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:tc:postgresql:17:///fgc_exception_review?currentSchema=fgc")
@Transactional
@WithMockUser(roles="GA_ADMIN")
class ExceptionSourceReviewIntegrationTest {
    @Autowired ExceptionSourceReviewService review;
    @Autowired ExceptionCaseService actions;
    @Autowired CapCheckService caps;
    @Autowired com.susukkang.fgc.arbitrage.service.ArbitrageService arbitrage;
    @Autowired com.susukkang.fgc.transaction.service.CommissionPaymentService payments;
    @Autowired JdbcTemplate jdbc;
    Long contract;
    @BeforeEach void fixture() {
        contract = jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,agent_id,organization_id,
                  premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,premium_conversion_rule_code,
                  payment_cycle_code,payment_term_months,current_status,data_origin)
                SELECT insurer_id,product_offering_id,?,contract_date,agent_id,organization_id,premium_per_cycle_amount,
                  first_premium_amount,monthly_equivalent_first_premium,premium_conversion_rule_code,payment_cycle_code,payment_term_months,'ACTIVE','MANUAL'
                FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id
                """,Long.class,"IT-REVIEW-"+UUID.randomUUID());
    }
    Long exception(String type, String sourceType, String sourceId) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.exception_case(exception_key,exception_type,severity,status,contract_id,assigned_to,source_entity_type,source_entity_id,title)
                VALUES (?,?, 'CRITICAL','IN_REVIEW',?,1,?,?,'원천 검토') RETURNING exception_case_id
                """,Long.class,"IT-REVIEW-"+UUID.randomUUID(),type,contract,sourceType,sourceId);
    }
    @Test void insurerCapRequiresAdministratorAndDocumentButCommentKeepsReviewOpen() {
        var cap = caps.calculateAndSave(CapCalculationCommand.manual(contract,PaymentStage.INSURER_TO_GA,LocalDate.of(2026,11,1),null));
        Long id=exception("CAP_VIOLATION","CAP_CHECK",cap.capCheckId().toString());
        var context=review.context(id,1L);
        assertThat(context.administratorReviewRequired()).isTrue();
        assertThat(context.rejectionRequiresEvidence()).isTrue();
        assertThat(actions.userAction(id,new ExceptionActionRequest(ExceptionActionType.COMMENT,"보험사 지급기준 확인 요청",null),1L,"admin").toStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThatThrownBy(() -> actions.userAction(id,new ExceptionActionRequest(ExceptionActionType.REJECT,"보험사 시정 요청",null),1L,"admin")).isInstanceOf(FgcBusinessException.class);
        assertThat(actions.userAction(id,new ExceptionActionRequest(ExceptionActionType.REJECT,"보험사 시정 요청 · GA→설계사 한도 확인","INS-REQ-1"),1L,"admin").toStatus()).isEqualTo(ExceptionStatus.REJECTED);
    }
    @Test @WithMockUser(roles="SETTLEMENT") void settlementCannotRejectInsurerScheduledCapOrTakeOverAnotherReviewer() {
        var cap=caps.calculateAndSave(CapCalculationCommand.manual(contract,PaymentStage.INSURER_TO_GA,LocalDate.of(2026,11,1),null));
        Long id=exception("CAP_VIOLATION","CAP_CHECK",cap.capCheckId().toString());
        assertThatThrownBy(() -> actions.userAction(id,new ExceptionActionRequest(ExceptionActionType.REJECT,"시정 요청","INS-1"),1L,"settle"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> review.context(id,2L)).isInstanceOf(FgcBusinessException.class);
    }
    @Test void futureOfferingRoutesToContractCorrectionAndCannotBeResolvedByUnchangedPolicy() {
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date='2020-01-01' WHERE contract_id=?",contract);
        Long id=exception("POLICY_MISSING","ARBITRAGE_CHECK","999999");
        var context=review.context(id,1L);
        assertThat(context.offeringMismatch()).isTrue();
        assertThat(context.links()).anySatisfy(link -> assertThat(link.label()).isEqualTo("계약 판매버전 수정"));
        assertThat(review.recheck(id,1L,"admin").resolved()).isFalse();
    }
    @Test void warningCanBeRejectedWithJudgmentWithoutChangingFinancialRecords() {
        Long id=exception("CAP_WARNING","IT","notice");
        Long before=jdbc.queryForObject("SELECT count(*) FROM fgc.commission_transaction",Long.class);
        var result=actions.userAction(id,new ExceptionActionRequest(ExceptionActionType.REJECT,"잔여 한도 확인 · 추가 지급 전 사전검증",null),1L,"admin");
        assertThat(result.toStatus()).isEqualTo(ExceptionStatus.REJECTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.commission_transaction",Long.class)).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @WithMockUser(roles="SETTLEMENT")
    void arbitrageRefundRecheckUsesOriginalCutoffAndOnlyClosesAfterConfirmedDeduction(boolean refunded) {
        var asOf=LocalDate.of(2026,11,30);
        jdbc.update("INSERT INTO fgc.contract_financial_snapshot(contract_id,as_of_date,contract_month_no,cumulative_paid_premium,surrender_value,surrender_value_type,source_ref) VALUES (?, ?,5,0,0,'ACTUAL','IT-REFUND')",contract,asOf);
        registerPayment(asOf,"PAYMENT","BASE_COMMISSION");
        // 수동 재검증은 실행 번호를 별도 트랜잭션에서 할당한다. 테스트 픽스처가 같은
        // 월의 미커밋 실행 번호를 점유하지 않도록 다른 월에 원본 실행을 둔다.
        var originalMonth=asOf.minusMonths(1).withDayOfMonth(1);
        Long run=jdbc.queryForObject("INSERT INTO fgc.validation_run(validation_month,run_no,status) SELECT ?,COALESCE(MAX(run_no),0)+1,'CREATED' FROM fgc.validation_run WHERE validation_month=? RETURNING validation_run_id",Long.class,originalMonth,originalMonth);
        var original=arbitrage.checkInExistingRun(run,contract,asOf);
        assertThat(original.getResultStatus()).isEqualTo(ArbitrageCheckStatus.CANDIDATE);
        Long id=exception("ARBITRAGE_CANDIDATE","ARBITRAGE_CHECK",original.getArbitrageCheckId().toString());
        if (refunded) registerPayment(asOf,"DEDUCTION","CLAWBACK");
        var result=review.recheck(id,1L,"admin");
        assertThat(result.resolved()).isEqualTo(refunded);
        if (refunded) assertThat(result.action().actionType()).isEqualTo("REDUCE");
        else assertThat(result.action()).isNull();
        assertThat(jdbc.queryForObject("SELECT as_of_date FROM fgc.arbitrage_check WHERE contract_id=? ORDER BY arbitrage_check_id DESC LIMIT 1",LocalDate.class,contract)).isEqualTo(asOf);
    }

    private void registerPayment(LocalDate date,String cashflow,String itemCode) {
        Long agent=jdbc.queryForObject("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id=?",Long.class,contract);
        Long item=jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code=?",Long.class,itemCode);
        var amount=new java.math.BigDecimal("100");
        var body=new com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT","IT-REFUND-"+UUID.randomUUID(),
                contract,agent,item,amount,date.withDayOfMonth(1),cashflow,date,PaymentStage.GA_TO_FC,null,java.util.List.of(
                new com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest(contract,date,amount,InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,"실제 지급·환수 확인","DIRECT","REC-1",AttributionMethod.DIRECT)),"REC-1",null);
        var payment=payments.create(body);
        payments.confirm(payment.paymentId(),"IT-REFUND-"+payment.paymentId());
    }
}
