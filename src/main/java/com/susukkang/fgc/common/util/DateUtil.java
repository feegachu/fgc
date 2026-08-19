package com.susukkang.fgc.common.util;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

public final class DateUtil {

    public static final ZoneId SEOUL_ZONE =
            ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter SETTLEMENT_MONTH_FORMATTER =
            DateTimeFormatter
                    .ofPattern("uuuu-MM")
                    .withResolverStyle(ResolverStyle.STRICT);

    private DateUtil() {
    }

    public static LocalDate parseSettlementMonth(String value) {
        return YearMonth
                .parse(value, SETTLEMENT_MONTH_FORMATTER)
                .atDay(1);
    }

    public static String formatSettlementMonth(LocalDate value) {
        return YearMonth
                .from(value)
                .format(SETTLEMENT_MONTH_FORMATTER);
    }

    public static OffsetDateTime nowSeoul() {
        return OffsetDateTime.now(SEOUL_ZONE);
    }

    /** PostgreSQL JDBC가 timestamptz를 UTC OffsetDateTime으로 반환해도 업무 화면은 서울 시각으로 표시한다. */
    public static OffsetDateTime toSeoul(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(SEOUL_ZONE).toOffsetDateTime();
    }
}
