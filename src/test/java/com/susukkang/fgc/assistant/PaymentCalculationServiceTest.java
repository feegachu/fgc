package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.assistant.dto.PaymentCalculationRequest;
import com.susukkang.fgc.assistant.service.PaymentCalculationService;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentCalculationServiceTest {
    CommissionPaymentService payments;
    PaymentCalculationService service;
    @BeforeEach void setup() {
        payments = mock(CommissionPaymentService.class);
        service = new PaymentCalculationService(payments, new ObjectMapper().findAndRegisterModules());
        role("SETTLEMENT");
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    void role(String role) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role)))); }
    PaymentCalculationRequest request(PaymentCalculationRequest.Kind kind) { return new PaymentCalculationRequest("assistant.v1", kind, PaymentCalculationRequest.Mode.EXISTING, new PaymentCalculationRequest.Scope("77"), null, 3L); }
    static CommissionPaymentResponse payment(CommissionPaymentStatus status, String amount, String... rows) {
        var attrs = java.util.Arrays.stream(rows).map(value -> new CommissionPaymentAttributionResponse(1, 3L, LocalDate.of(2026,10,1), LocalDate.of(2026,10,1), value == null ? null : new BigDecimal(value), InclusionDecisionStatus.INCLUDED, null, null, null, null, AttributionMethod.DIRECT)).toList();
        return new CommissionPaymentResponse(77L, "GA_MANUAL_PAYMENT", "test", null, 1L, 1L, "BASE", "base", new BigDecimal(amount), LocalDate.of(2026,10,1), "PAYMENT", null, PaymentStage.GA_TO_FC, status, null, attrs, List.of(), null, null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }
    @Test void computesRoundedEachLineAndNegativeDifferenceWithoutWrites() {
        when(payments.get(77L)).thenReturn(payment(CommissionPaymentStatus.DRAFT,"1.49","0.50","0.50"));
        var result = service.calculate(request(PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE));
        assertThat(result.amounts()).containsEntry("amount","1").containsEntry("attributedTotal","2").containsEntry("differenceAmount","-1");
        assertThat(result.basis().lines()).hasSize(2).allSatisfy(row -> {
            assertThat(row.originalAmount()).isEqualTo("0.50");
            assertThat(row.roundedAmount()).isEqualTo("1");
            assertThat(row.contractId()).isEqualTo("3");
        });
        assertThat(result.basis().asOfDate()).isEqualTo("2026-10-01");
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED"); assertThat(result.validity()).isNull(); assertThat(result.recommendation().allowed()).isFalse(); assertThat(result.snapshotOnly()).isTrue(); assertThat(result.paymentStatus()).isEqualTo("DRAFT");
        verify(payments).get(77L); verifyNoMoreInteractions(payments);
    }
    @Test void returnsTenThousandUnattributedAndNeverCallsPrecheckForBalance() {
        role("COMPLIANCE"); when(payments.get(77L)).thenReturn(payment(CommissionPaymentStatus.CONFIRMED,"100000","60000","30000"));
        var result = service.calculate(request(PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE));
        assertThat(result.amounts()).containsEntry("differenceAmount","10000"); assertThat(result.confirmable()).isNull(); verify(payments).get(77L); verifyNoMoreInteractions(payments);
    }
    @Test void nullAndMissingAttributionsAreNotZero() {
        when(payments.get(77L)).thenReturn(payment(CommissionPaymentStatus.DRAFT,"100",(String)null));
        var result = service.calculate(request(PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE));
        assertThat(result.status()).isEqualTo("INSUFFICIENT_DATA"); assertThat(result.amounts()).isEmpty();
    }
    @Test void missingScopeAndUnsupportedModesDoNotReadSources() {
        var request = request(PaymentCalculationRequest.Kind.EXPECTED_COMMISSION);
        assertThat(service.calculate(request).status()).isEqualTo("UNSUPPORTED");
        assertThat(service.calculate(new PaymentCalculationRequest("assistant.v1", PaymentCalculationRequest.Kind.ATTRIBUTION_BALANCE, PaymentCalculationRequest.Mode.EXISTING, null, null, 0L)).status()).isEqualTo("INSUFFICIENT_DATA");
        verifyNoInteractions(payments);
    }
    @Test void candidateRequiresActualProcessRoleEvenIfCalledDirectly() {
        role("COMPLIANCE"); assertThatThrownBy(() -> service.calculate(request(PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE))).isInstanceOf(AccessDeniedException.class); verifyNoInteractions(payments);
    }
    @Test void nonDraftDoesNotInvokePrecheck() {
        when(payments.get(77L)).thenReturn(payment(CommissionPaymentStatus.CONFIRMED,"100","100"));
        assertThat(service.calculate(request(PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE)).status()).isEqualTo("UNSUPPORTED"); verify(payments).get(77L); verifyNoMoreInteractions(payments);
    }
    @Test void candidatePreservesExistingBlockersAndDoesNotGrantProof() {
        when(payments.get(77L)).thenReturn(payment(CommissionPaymentStatus.DRAFT,"100","90"));
        when(payments.precheck(77L)).thenReturn(new TransactionPrecheckResponse(77L,List.of(),List.of(new TransactionPrecheckResponse.Blocker("FGC-TRAN-003","차액",3L,9L)),false));
        var result = service.calculate(request(PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE));
        assertThat(result.confirmable()).isFalse(); assertThat(result.status()).isEqualTo("REVIEW_REQUIRED"); assertThat(result.blockers().getFirst().transactionAttributionId()).isEqualTo("9"); assertThat(result.validity()).isNull();
        verify(payments).get(77L); verify(payments).precheck(77L); verifyNoMoreInteractions(payments);
    }

    @Test void typedCapPreviewKeepsNullAndLargeWonStringsAndNeverCreatesProof() {
        when(payments.get(77L)).thenReturn(payment(CommissionPaymentStatus.DRAFT,"100","100"));
        var preview = new TransactionPrecheckResponse.CapPreviewItem(3L,"C-3",PaymentStage.GA_TO_FC,"GA→설계사",LocalDate.of(2026,10,1),100L,null,null,999999999999999L,0L,100L,100L,null,"0.1",CapResultStatus.REVIEW_REQUIRED,"검토필요",9L,null,null,null);
        when(payments.precheck(77L)).thenReturn(new TransactionPrecheckResponse(77L,List.of(preview),List.of(),true));
        var result = service.calculate(request(PaymentCalculationRequest.Kind.PAYMENT_CANDIDATE));
        assertThat(result.capPreview().getFirst()).containsEntry("limitAmount","999999999999999").containsEntry("contractId","3");
        assertThat(result.capPreview().getFirst().get("remainingAmount")).isNull();
        assertThat(result.validity()).isNull(); assertThat(result.recommendation().allowed()).isFalse();
    }
    public record CompatibilityOutput(String answer) {}
    @Test void openAiStructuredRequestStillBuildsWithJakartaAnnotations() {
        var request = com.openai.models.responses.ResponseCreateParams.builder().model("local-test").input("not sent").text(CompatibilityOutput.class).build();
        assertThat(request).isNotNull();
    }
    @Test void unsupportedCalculationKindIsReportedBeforeCandidateValidation() {
        var request = new PaymentCalculationRequest("assistant.v1", PaymentCalculationRequest.Kind.CAP_STATUS,
                PaymentCalculationRequest.Mode.EXISTING, new PaymentCalculationRequest.Scope("77"),
                new ObjectMapper().createObjectNode().put("amount", "100000"), 0L);
        assertThat(service.calculate(request).status()).isEqualTo("UNSUPPORTED");
        verifyNoInteractions(payments);
    }
}
