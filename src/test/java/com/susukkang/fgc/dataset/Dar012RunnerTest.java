package com.susukkang.fgc.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class Dar012RunnerTest {
    private static final Dar012Verifier.ScenarioResult MATCHED = new Dar012Verifier.ScenarioResult("REC", 3, 3, List.of());
    private static final Dar012Verifier.ConsistencyCheck CONSISTENT =
            new Dar012Verifier.ConsistencyCheck("exceptions", 1, 1, 1, 1, 1, true);

    private static final Dar012Verifier.DetailSample SAME_DETAIL = new Dar012Verifier.DetailSample("CAP_WARNING", "GA_TO_FC", 2, 2);
    private static final Dar012Verifier.ClawbackCheck CALCULATED =
            new Dar012Verifier.ClawbackCheck("C-1", BigDecimal.TEN, BigDecimal.ONE, List.of());

    private static boolean mismatch(Dar012Verifier.ScenarioResult answer, Dar012Verifier.ConsistencyCheck aggregate,
                                    Dar012Verifier.DetailSample detail, Dar012Verifier.ClawbackCheck clawback) {
        return Dar012Runner.hasMismatch(List.of(answer), List.of(aggregate), List.of(detail), clawback);
    }

    @Test
    void everyAcceptanceCheckCanFailTheRun() {
        assertThat(mismatch(MATCHED, CONSISTENT, SAME_DETAIL, CALCULATED)).isFalse();
        assertThat(mismatch(new Dar012Verifier.ScenarioResult("REC", 3, 2, List.of("x")), CONSISTENT, SAME_DETAIL, CALCULATED)).isTrue();
        assertThat(mismatch(MATCHED, new Dar012Verifier.ConsistencyCheck("exceptions", 1, 2, 1, 1, 1, false), SAME_DETAIL, CALCULATED)).isTrue();
        assertThat(mismatch(MATCHED, CONSISTENT, new Dar012Verifier.DetailSample("CAP_WARNING", "GA_TO_FC", 2, 1), CALCULATED)).isTrue();
    }

    @Test
    void aRepresentativeClawbackThatWasNotCalculatedFailsTheRun() {
        for (var clawback : List.of(
                new Dar012Verifier.ClawbackCheck("C-1", BigDecimal.TEN, BigDecimal.ZERO, List.of()),
                new Dar012Verifier.ClawbackCheck("C-1", BigDecimal.TEN, null, List.of()),
                new Dar012Verifier.ClawbackCheck("C-1", BigDecimal.TEN, BigDecimal.ONE, List.of("정책 없음")))) {
            assertThat(mismatch(MATCHED, CONSISTENT, SAME_DETAIL, clawback)).as(clawback.toString()).isTrue();
        }
    }

    @Test
    void anUnwritableOutputPathStopsBeforeLoading(@TempDir Path temp) throws IOException {
        Path notADirectory = Files.createFile(temp.resolve("output"));
        Dar012Properties properties = new Dar012Properties("D12-T", null, null, LocalDate.of(2026, 9, 1), notADirectory,
                null, null, null);
        Dar012Loader loader = mock(Dar012Loader.class);

        assertThatThrownBy(() -> Dar012Runner.generate(loader, properties)).isInstanceOf(IOException.class);

        verify(loader, never()).load(any(), any());
    }
}
