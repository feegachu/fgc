package com.susukkang.fgc.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RecipientPairingNotDueYetTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private static boolean notDueYet(List<String> actual, String... dueDates) {
        return RecipientPairing.notDueYet(new RecipientPairing.Pair<>(Arrays.asList(dueDates), actual),
                (Function<String, LocalDate>) date -> date == null ? null : LocalDate.parse(date), TODAY);
    }

    @Test
    void 이번_달_뒤쪽_회차만_있고_실제가_없으면_아직_누락이_아니다() {
        assertThat(notDueYet(List.of(), "2026-11-07", "2026-11-30")).isTrue();
    }

    @Test
    void 오늘까지_도래한_회차가_섞이면_누락_판정_대상이다() {
        assertThat(notDueYet(List.of(), "2026-11-06")).isFalse();
        assertThat(notDueYet(List.of(), "2026-11-05", "2026-11-07")).isFalse();
    }

    @Test
    void 실제가_있거나_지급예정일이_없거나_미래_달이면_그대로_판정한다() {
        assertThat(notDueYet(List.of("지급"), "2026-11-07")).isFalse();
        assertThat(notDueYet(List.of(), (String) null)).isFalse();
        assertThat(notDueYet(List.of(), "2026-12-01")).isFalse();
        assertThat(notDueYet(List.of())).isFalse();
    }
}
