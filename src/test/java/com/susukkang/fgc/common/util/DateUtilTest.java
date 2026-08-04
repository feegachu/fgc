package com.susukkang.fgc.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateUtilTest {

    @Test
    void 정산월을_해당_월의_첫날로_변환한다() {
        assertThat(DateUtil.parseSettlementMonth("2026-08"))
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void 날짜를_정산월_문자열로_변환한다() {
        assertThat(DateUtil.formatSettlementMonth(LocalDate.of(2026, 8, 31)))
                .isEqualTo("2026-08");
    }

    @Test
    void 잘못된_정산월은_허용하지_않는다() {
        assertThatThrownBy(() -> DateUtil.parseSettlementMonth("2026-13"))
                .isInstanceOf(DateTimeParseException.class);
    }
}
