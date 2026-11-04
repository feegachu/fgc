package com.susukkang.fgc.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.assistant.dto.*;
import com.susukkang.fgc.assistant.service.*;
import com.susukkang.fgc.schedule.repository.ScheduleQueryRepository;
import com.susukkang.fgc.schedule.dto.*;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultQueryService;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AssistantCalculationSourcesTest {
    ScheduleQueryRepository schedules=mock(ScheduleQueryRepository.class);
    CapCalculator caps=mock(CapCalculator.class);
    ReconciliationResultQueryService reconciliations=mock(ReconciliationResultQueryService.class);
    AgentDataScopeService access=mock(AgentDataScopeService.class);
    AssistantCalculationSources service=new AssistantCalculationSources(schedules,caps,reconciliations,access);
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Test void hypotheticalBalanceRoundsEachLineWithoutAnySourceRead() throws Exception {
        var request=new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE,PaymentCalculationRequest.Mode.HYPOTHETICAL,null,mapper.readTree("{\"balance\":{\"amount\":\"100000\",\"attributionAmounts\":[\"60000\",\"30000\"]}}"),4L);
        var result=service.calculate(request);assertThat(result.amounts()).containsEntry("differenceAmount","10000");assertThat(result.recommendation().allowed()).isFalse();
        var rounding=new PaymentCalculationRequest("assistant.v1",request.kind(),request.mode(),null,mapper.readTree("{\"balance\":{\"amount\":\"1.49\",\"attributionAmounts\":[\"0.50\",\"0.50\"]}}"),5L);
        assertThat(service.calculate(rounding).amounts()).containsEntry("differenceAmount","-1");verifyNoInteractions(schedules,caps,reconciliations,access);
    }
    @Test void rejectsFloatingPointAndExponentInsteadOfCoercing() throws Exception {
        for(String amount:List.of("100.0","\"1e3\"","\"-1\"","\"10000000000000\"","\"0.001\"")) {
            var candidate=mapper.readTree("{\"balance\":{\"amount\":"+amount+",\"attributionAmounts\":[\"0\"]}}");
            assertThatThrownBy(()->service.calculate(new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE,PaymentCalculationRequest.Mode.HYPOTHETICAL,null,candidate,1L))).isInstanceOf(com.susukkang.fgc.common.exception.FgcBusinessException.class);
        }
    }
    PaymentCalculationRequest expected(PaymentCalculationRequest.Scope.Aggregation aggregate,Integer installment) {
        return new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.EXPECTED_COMMISSION,PaymentCalculationRequest.Mode.EXISTING,new PaymentCalculationRequest.Scope(null,"3","2026-10",PaymentStage.GA_TO_FC,installment,"4","5",null,null,aggregate),null,1L);
    }
    void schedule(String... amounts) {
        var lines=new ArrayList<ScheduleLineResponse>();int seq=0;
        for(String amount:amounts) lines.add(ScheduleLineResponse.builder().contractId(3L).commissionItemId(4L).beneficiaryAgentId(5L).lineNo(++seq).installmentNo(seq).dueDate(LocalDate.of(2026,10,9)).attributionDate(LocalDate.of(2026,10,8)).expectedAmount(new BigDecimal(amount)).lineStatus("PLANNED").ruleRef(6L).build());
        when(schedules.selectByContractIdAndPaymentStage(3L,PaymentStage.GA_TO_FC)).thenReturn(new ScheduleDetailResponse(7L,ScheduleHeaderResponse.builder().generatedAt(OffsetDateTime.now()).build(),lines));
    }
    @Test void explicitMonthAggregationAndExactInstallmentUseStoredLinesOnly() {
        schedule("0.50","0.50");var monthly=service.calculate(expected(PaymentCalculationRequest.Scope.Aggregation.MONTH_TOTAL,null));
        assertThat(monthly.amounts()).containsEntry("expectedAmount","2");assertThat(monthly.lines()).hasSize(2);
        var single=service.calculate(expected(PaymentCalculationRequest.Scope.Aggregation.SINGLE_LINE,2));assertThat(single.amounts()).containsEntry("expectedAmount","1");assertThat(single.lines().getFirst().attributionDate()).isEqualTo("2026-10-08");verify(access,times(2)).requireContract(3L);
    }
    @Test void absentScheduleNeverGeneratesOneAndMissingSelectionNeverPicksFirst() {
        var missing=service.calculate(expected(PaymentCalculationRequest.Scope.Aggregation.SINGLE_LINE,null));assertThat(missing.status()).isEqualTo("INSUFFICIENT_DATA");verifyNoInteractions(schedules);
        assertThat(service.calculate(expected(PaymentCalculationRequest.Scope.Aggregation.MONTH_TOTAL,null)).missingInputs()).contains("activeSchedule");
    }
    @Test void capForwardsEngineAmountsUsageAndRuleWithoutRecomputing() {
        var cap=mock(com.susukkang.fgc.cap.dto.CapCalculationResult.class);
        when(cap.resultStatus()).thenReturn(com.susukkang.fgc.common.code.CapResultStatus.WARNING);when(cap.capRuleSetId()).thenReturn(9L);when(cap.limitAmount()).thenReturn(new BigDecimal("1000"));when(cap.includedAmount()).thenReturn(new BigDecimal("900"));when(cap.remainingAmount()).thenReturn(new BigDecimal("100"));when(cap.usagePct()).thenReturn(new BigDecimal("90.000000"));when(caps.calculate(any())).thenReturn(cap);
        var request=new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.CAP_STATUS,PaymentCalculationRequest.Mode.EXISTING,new PaymentCalculationRequest.Scope(null,"3",null,PaymentStage.GA_TO_FC,null,null,null,LocalDate.of(2026,10,8),null,null),null,1L);
        var result=service.calculate(request);assertThat(result.amounts()).containsEntry("remainingAmount","100");assertThat(result.capPreview().getFirst()).containsEntry("usagePct","90.000000").containsEntry("resultStatus","WARNING");assertThat(result.basis().ruleIds()).containsExactly("9");assertThat(result.recommendation().allowed()).isFalse();
        verify(caps).calculate(com.susukkang.fgc.cap.dto.CapCalculationCommand.realtime(3L,PaymentStage.GA_TO_FC,LocalDate.of(2026,10,8),null));verifyNoInteractions(schedules,reconciliations);
    }
    @Test void reconciliationPreservesStoredTotalsAndScopeAuthorization() {
        var detail=mock(com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailResponse.class);
        when(detail.contractId()).thenReturn(3L);when(detail.expectedTotalAmount()).thenReturn(new BigDecimal("100000"));when(detail.actualTotalAmount()).thenReturn(new BigDecimal("50000"));when(detail.differenceAmount()).thenReturn(new BigDecimal("50000"));when(reconciliations.get(99L)).thenReturn(detail);
        var request=new PaymentCalculationRequest("assistant.v1",PaymentCalculationRequest.Kind.RECONCILIATION_DIFFERENCE,PaymentCalculationRequest.Mode.EXISTING,new PaymentCalculationRequest.Scope(null,null,null,null,null,null,null,null,"99",null),null,1L);
        var result=service.calculate(request);assertThat(result.amounts()).containsEntry("differenceAmount","50000").containsEntry("actualAmount","50000");assertThat(result.recommendation().allowed()).isFalse();verify(access).requireContract(3L);verifyNoInteractions(caps,schedules);
    }
}
