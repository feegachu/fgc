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

    // 2026-08-11 yslee - 서로 다른 지급 건의 확정 멱등키 충돌 응답 검증
    // 기존 코드: 지급 건 자연키 제약만 거래 중복 오류로 변환하는지 테스트
    // 문제: 새 멱등키 고유 제약이 매핑에서 빠져도 회귀 테스트가 감지하지 못함
    // 개선: 멱등키 제약명이 FGC-TRAN-001로 해석되는지 별도 검증
    @Test
    void resolvesConfirmationIdempotencyKeyConstraint() {
        RuntimeException exception = new RuntimeException(
                "duplicate key violates constraint uq_commission_transaction_confirm_idempotency"
        );

        assertThat(resolver.resolve(exception))
                .contains(FgcErrorCode.TRAN_001);
    }

    @Test
    void resolvesValidationFinalizationIdempotencyKeyConstraintBeforeRunNaturalKey() {
        RuntimeException exception = new RuntimeException(
                "duplicate key violates constraint uq_validation_run_finalize_idempotency"
        );

        assertThat(resolver.resolve(exception))
                .contains(FgcErrorCode.VRUN_005);
    }
}
