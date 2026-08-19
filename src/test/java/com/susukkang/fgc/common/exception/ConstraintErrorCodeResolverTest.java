package com.susukkang.fgc.common.exception;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    // 2026-08-12 yslee - 대사 실행 중복과 결과 그룹 중복 오류 분리 회귀 테스트
    // 기존 코드: 대사 결과 그룹 중복 제약만 테스트
    // 문제: uq_reconciliation_run을 결과 그룹 중복과 같은 코드로 처리하면 장애 원인을 구분하지 못함
    // 개선: 대사 실행 업무키 제약을 FGC-RECO-002로 변환하는지 검증
    @Test
    void resolvesReconciliationRunConstraint() {
        RuntimeException exception = new RuntimeException(
                "duplicate key violates constraint uq_reconciliation_run"
        );

        assertThat(resolver.resolve(exception))
                .contains(FgcErrorCode.RECO_002);
    }

    @Test
    void resolvesReconciliationResultConstraint() {
        RuntimeException exception = new RuntimeException(
                "duplicate key violates constraint uq_reconciliation_result"
        );

        assertThat(resolver.resolve(exception))
                .contains(FgcErrorCode.RECO_001);
    }

    @Test
    void resolvesCurrentPostedJournalSourceConstraintAsAlreadyPosted() {
        RuntimeException exception = new RuntimeException(
                "duplicate key violates constraint uq_journal_current_posted_source"
        );

        assertThat(resolver.resolve(exception))
                .contains(FgcErrorCode.LEDG_002);
    }

    @Test
    void resolvesJournalCorrectionConstraintsSeparatelyFromAlreadyPosted() {
        List<String> correctionConstraints = List.of(
                "uq_journal_single_reversal",
                "uq_journal_correction_original",
                "uq_journal_correction_group_reversal",
                "uq_journal_correction_group_repost",
                "uq_journal_source_revision"
        );

        assertThat(correctionConstraints).allSatisfy(constraint -> {
            RuntimeException exception = new RuntimeException(
                    "duplicate key violates constraint " + constraint
            );

            assertThat(resolver.resolve(exception))
                    .contains(FgcErrorCode.LEDG_004);
        });
    }
}
