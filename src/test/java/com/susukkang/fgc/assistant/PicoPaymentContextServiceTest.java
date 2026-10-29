package com.susukkang.fgc.assistant;

import com.susukkang.fgc.assistant.service.PicoPaymentContextService;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PicoPaymentContextServiceTest {
    CommissionPaymentService payments = mock(CommissionPaymentService.class);
    PicoPaymentContextService service = new PicoPaymentContextService(payments, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    @Test void returnsOnlyTheStoredStateWithoutPrecheckOrWrites() {
        when(payments.get(77L)).thenReturn(PaymentCalculationServiceTest.payment(CommissionPaymentStatus.DRAFT,"100000","100000"));
        assertThat(service.read(77L).status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        verify(payments).get(77L); verifyNoMoreInteractions(payments);
    }
    @Test void mapsOnlyMissingPaymentTo404AndPreservesAuthorization() {
        when(payments.get(77L)).thenThrow(new FgcBusinessException(FgcErrorCode.COMMON_002,"paymentId",Map.of("field","paymentId"),"missing"));
        assertThatThrownBy(() -> service.read(77L)).isInstanceOfSatisfying(FgcBusinessException.class,error -> assertThat(error.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
        when(payments.get(88L)).thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.read(88L)).isInstanceOf(AccessDeniedException.class);
        var other = new FgcBusinessException(FgcErrorCode.COMMON_002,"other",Map.of(),"invalid");
        when(payments.get(99L)).thenThrow(other);
        assertThatThrownBy(() -> service.read(99L)).isSameAs(other);
    }
    @Test void rejectsMismatchedOrInvalidReferencesInsteadOfUsingAnotherPayment() {
        when(payments.get(88L)).thenReturn(PaymentCalculationServiceTest.payment(CommissionPaymentStatus.DRAFT,"100","100"));
        assertThatThrownBy(() -> service.read(88L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.read(0L)).isInstanceOf(FgcBusinessException.class);
        assertThatThrownBy(() -> service.read(null)).isInstanceOf(FgcBusinessException.class);
    }
}
