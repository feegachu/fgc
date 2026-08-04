package com.susukkang.fgc.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
