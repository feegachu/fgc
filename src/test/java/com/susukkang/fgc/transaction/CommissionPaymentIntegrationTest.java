package com.susukkang.fgc.transaction;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설명 : 수수료 지급 건 PostgreSQL 통합 테스트
 *
 * @author yslee
 * @since 2026-08-07
 * @version 1.2
 */
@SpringBootTest
@Transactional
class CommissionPaymentIntegrationTest {

    @Autowired
    private CommissionPaymentService commissionPaymentService;

    @Test
    void persistsAndConfirmsPaymentAgainstProjectErd() {
        CommissionPaymentCreateRequest request = request(
                "IT-FUN065-" + UUID.randomUUID(),
                1
        );

        CommissionPaymentResponse created = commissionPaymentService.create(request);
        CommissionPaymentResponse confirmed = commissionPaymentService.confirm(
                created.paymentId()
        );

        assertThat(created.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(confirmed.attributedContractId()).isEqualTo(1L);
        assertThat(confirmed.allocationPolicyVersion()).isEqualTo(4L);
        assertThat(confirmed.paymentSequence()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateManualPaymentNaturalKeyInDatabase() {
        String runId = UUID.randomUUID().toString();
        commissionPaymentService.create(request("IT-FUN065-A-" + runId, 99));

        assertThatThrownBy(() -> commissionPaymentService.create(
                request("IT-FUN065-B-" + runId, 99)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private CommissionPaymentCreateRequest request(
            String sourceBusinessKey,
            int paymentSequence
    ) {
        return new CommissionPaymentCreateRequest(
                sourceBusinessKey,
                paymentSequence,
                1L,
                6L,
                "BASE_COMMISSION",
                BigDecimal.ZERO,
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 31),
                PaymentStage.GA_TO_FC,
                1L,
                InclusionDecisionStatus.INCLUDED,
                "통합 테스트 직접 귀속",
                4L,
                "DIRECT",
                "IT-EVIDENCE",
                AttributionMethod.DIRECT,
                "rollback integration test"
        );
    }
}
