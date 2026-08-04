package com.susukkang.fgc.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintErrorCodeResolverTest {

    private final ConstraintErrorCodeResolver resolver =
            new ConstraintErrorCodeResolver();

    @Test
    void DB_제약조건_이름을_오류_코드로_변환한다() {
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
    void 등록되지_않은_제약조건이면_빈_결과를_반환한다() {
        RuntimeException exception = new RuntimeException(
                "unknown_constraint"
        );

        assertThat(resolver.resolve(exception)).isEmpty();
    }
}
