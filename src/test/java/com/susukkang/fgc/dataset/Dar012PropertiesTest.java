package com.susukkang.fgc.dataset;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Dar012PropertiesTest {

    private static Dar012Properties withBaseDate(LocalDate baseDate) {
        return new Dar012Properties("D12-T", null, null, baseDate, null);
    }

    @Test
    void rejectsBaseDateEarlierThanTheFixedAppointmentRange() {
        assertThatThrownBy(() -> withBaseDate(Dar012Properties.MIN_BASE_DATE.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("base-date");
    }

    @Test
    void acceptsTheLowerBoundAndTheDefault() {
        assertThat(withBaseDate(Dar012Properties.MIN_BASE_DATE).baseDate()).isEqualTo(Dar012Properties.MIN_BASE_DATE);
        assertThat(withBaseDate(null).baseDate()).isEqualTo(Dar012Properties.DEFAULT_BASE_DATE);
    }
}
