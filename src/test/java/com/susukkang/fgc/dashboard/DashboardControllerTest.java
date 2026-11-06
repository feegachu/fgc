package com.susukkang.fgc.dashboard;

import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.dashboard.controller.DashboardController;
import com.susukkang.fgc.dashboard.service.DashboardService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.mockito.Mockito.*;

class DashboardControllerTest {
    @Test
    void omittedMonthUsesSeoulAtUtcMonthBoundaryButExplicitMonthIsPreserved() {
        var instant = Instant.parse("2026-06-30T15:30:00Z");
        var utcMonth = YearMonth.now(Clock.fixed(instant, ZoneOffset.UTC));
        var seoulMonth = YearMonth.now(Clock.fixed(instant, DateUtil.SEOUL_ZONE));
        var service = mock(DashboardService.class);
        var controller = new DashboardController(service);
        try (var months = mockStatic(YearMonth.class, CALLS_REAL_METHODS)) {
            months.when(YearMonth::now).thenReturn(utcMonth);
            months.when(() -> YearMonth.now(DateUtil.SEOUL_ZONE)).thenReturn(seoulMonth);
            controller.monthlyCommissions(null);
            controller.monthlyCommissions("2026-06");
            verify(service).monthlyCommissions(seoulMonth.atDay(1));
            verify(service).monthlyCommissions(utcMonth.atDay(1));
        }
    }
}
