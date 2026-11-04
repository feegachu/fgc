package com.susukkang.fgc.dataset;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Dar012VerifierTest {

    @Test
    void amountMismatchNeedsAtLeastOneDifferenceRowRegardlessOfOrder() {
        assertThat(Dar012Verifier.judgeReconciliation("RECON_AMOUNT_MISMATCH", List.of("AMOUNT_DIFFERENCE"))).isTrue();
        assertThat(Dar012Verifier.judgeReconciliation("RECON_AMOUNT_MISMATCH", List.of("ACTUAL_MISSING", "AMOUNT_DIFFERENCE"))).isTrue();
        assertThat(Dar012Verifier.judgeReconciliation("RECON_AMOUNT_MISMATCH", List.of("AMOUNT_DIFFERENCE", "ACTUAL_MISSING"))).isTrue();
        assertThat(Dar012Verifier.judgeReconciliation("RECON_AMOUNT_MISMATCH", List.of("MATCHED"))).isFalse();
        assertThat(Dar012Verifier.judgeReconciliation("RECON_AMOUNT_MISMATCH", List.of())).isFalse();
    }

    @Test
    void missingStatementNeedsAnActualMissingRow() {
        assertThat(Dar012Verifier.judgeReconciliation("RECON_MISSING_STATEMENT", List.of("MATCHED", "ACTUAL_MISSING"))).isTrue();
        assertThat(Dar012Verifier.judgeReconciliation("RECON_MISSING_STATEMENT", List.of("MATCHED"))).isFalse();
    }

    @Test
    void capBoundaryAnswersNeedTheEngineStatusAndTheExactRemainingAmount() {
        assertThat(Dar012Verifier.judgeCap("CAP_AT_LIMIT", "WARNING", new BigDecimal("0.00"))).isTrue();
        assertThat(Dar012Verifier.judgeCap("CAP_AT_LIMIT", "WARNING", new BigDecimal("1.00"))).isFalse();
        assertThat(Dar012Verifier.judgeCap("CAP_AT_LIMIT", "NORMAL", new BigDecimal("0.00"))).isFalse();
        assertThat(Dar012Verifier.judgeCap("CAP_EXCEEDED", "VIOLATION", new BigDecimal("-1.00"))).isTrue();
        assertThat(Dar012Verifier.judgeCap("CAP_EXCEEDED", "VIOLATION", new BigDecimal("-2.00"))).isFalse();
        assertThat(Dar012Verifier.judgeCap("CAP_EXCEEDED", "WARNING", new BigDecimal("-1.00"))).isFalse();
        assertThat(Dar012Verifier.judgeCap("CAP_AT_LIMIT", null, null)).isFalse();
    }

    @Test
    void statusScenariosNeedEveryFirstInstallmentRowMatched() {
        assertThat(Dar012Verifier.judgeReconciliation("STATUS_LAPSED", List.of("MATCHED", "MATCHED"))).isTrue();
        assertThat(Dar012Verifier.judgeReconciliation("STATUS_LAPSED", List.of("MATCHED", "AMOUNT_DIFFERENCE"))).isFalse();
        assertThat(Dar012Verifier.judgeReconciliation("STATUS_LAPSED", List.of())).isFalse();
    }
}
