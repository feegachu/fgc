package com.susukkang.fgc.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

    @Test
    void anAnswerKeyOrAggregateMismatchFailsTheRun() {
        assertThat(Dar012Runner.hasMismatch(List.of(MATCHED), List.of(CONSISTENT))).isFalse();
        assertThat(Dar012Runner.hasMismatch(List.of(new Dar012Verifier.ScenarioResult("REC", 3, 2, List.of("x"))), List.of(CONSISTENT)))
                .isTrue();
        assertThat(Dar012Runner.hasMismatch(List.of(MATCHED),
                List.of(new Dar012Verifier.ConsistencyCheck("exceptions", 1, 2, 1, 1, 1, false)))).isTrue();
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
