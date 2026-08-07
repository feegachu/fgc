package com.susukkang.fgc.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 데이터베이스 제약조건 업무 오류 변환 테스트
 *
 * @author yslee
 * @since 2026-08-07
 * @version 1.2
 */
class ConstraintErrorCodeResolverTest {

    private final ConstraintErrorCodeResolver resolver =
            new ConstraintErrorCodeResolver();

    @Test
    void resolvesDatabaseConstraintNameToErrorCode() {
        RuntimeException exception = new RuntimeException(
                "outer",
                new RuntimeException(
                        "duplicate key violates constraint uq_contract_no"
                )
        );

        assertThat(resolver.resolve(exception))
                .contains(FgcErrorCode.CONT_001);
    }

    @Test
    void returnsEmptyForUnknownConstraintName() {
        RuntimeException exception = new RuntimeException(
                "unknown_constraint"
        );

        assertThat(resolver.resolve(exception)).isEmpty();
    }

    @Test
    void resolvesCommissionPaymentNaturalKeyConstraint() {
        RuntimeException exception = new RuntimeException(
                "duplicate key violates constraint uq_commission_payment_natural"
        );

        assertThat(resolver.resolve(exception))
                .contains(FgcErrorCode.TRAN_001);
    }
}
