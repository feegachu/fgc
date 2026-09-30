package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.cap.repository.CapExceptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CapCheckFailureRecordServiceTest {

    @Mock
    CapExceptionRepository capExceptionRepository;

    @Test
    void recordsFailureWithPaymentStage() {
        CapCheckFailureRecordService service =
                new CapCheckFailureRecordService(capExceptionRepository);

        service.record(118L, 10L, PaymentStage.GA_TO_FC, "계산 데이터 누락");

        verify(capExceptionRepository).recordCapCheckFailure(
                118L, 10L, "GA_TO_FC", "계산 데이터 누락");
    }
}
