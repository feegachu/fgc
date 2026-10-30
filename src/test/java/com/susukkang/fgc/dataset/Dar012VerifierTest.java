package com.susukkang.fgc.dataset;

import org.junit.jupiter.api.Test;

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
    void statusScenariosNeedEveryFirstInstallmentRowMatched() {
        assertThat(Dar012Verifier.judgeReconciliation("STATUS_LAPSED", List.of("MATCHED", "MATCHED"))).isTrue();
        assertThat(Dar012Verifier.judgeReconciliation("STATUS_LAPSED", List.of("MATCHED", "AMOUNT_DIFFERENCE"))).isFalse();
        assertThat(Dar012Verifier.judgeReconciliation("STATUS_LAPSED", List.of())).isFalse();
    }

    @Test
    void capBoundaryScenariosAreDeferredNotMatched() {
        assertThat(Dar012Verifier.isDeferred("CAP_AT_LIMIT")).isTrue();
        assertThat(Dar012Verifier.isDeferred("CAP_EXCEEDED")).isTrue();
        assertThat(Dar012Verifier.isDeferred("RECON_AMOUNT_MISMATCH")).isFalse();
    }
}
