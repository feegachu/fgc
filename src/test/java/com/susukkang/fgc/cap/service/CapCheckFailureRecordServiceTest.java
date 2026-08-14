package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CapCheckFailureRecordServiceTest {

    @Mock
    ExceptionCaseMapper exceptionCaseMapper;

    @Test
    void recordsFailureWithPaymentStage() {
        CapCheckFailureRecordService service =
                new CapCheckFailureRecordService(exceptionCaseMapper);

        service.record(118L, 10L, PaymentStage.GA_TO_FC, "계산 데이터 누락");

        verify(exceptionCaseMapper).insertCapCheckFailure(
                118L, 10L, "GA_TO_FC", "계산 데이터 누락");
    }
}
