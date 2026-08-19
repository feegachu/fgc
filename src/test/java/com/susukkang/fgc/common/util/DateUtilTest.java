package com.susukkang.fgc.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateUtilTest {

    @Test
    void parsesSettlementMonthAsFirstDayOfMonth() {
        assertThat(DateUtil.parseSettlementMonth("2026-08"))
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void formatsDateAsSettlementMonth() {
        assertThat(DateUtil.formatSettlementMonth(LocalDate.of(2026, 8, 31)))
                .isEqualTo("2026-08");
    }

    @Test
    void rejectsInvalidSettlementMonth() {
        assertThatThrownBy(() -> DateUtil.parseSettlementMonth("2026-13"))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void convertsDatabaseUtcOffsetToSameInstantInSeoul() {
        assertThat(DateUtil.toSeoul(OffsetDateTime.parse("2026-08-17T06:23:00Z")))
                .isEqualTo(OffsetDateTime.parse("2026-08-17T15:23:00+09:00"));
        assertThat(DateUtil.toSeoul(null)).isNull();
    }
}
