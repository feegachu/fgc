package com.susukkang.fgc.reconciliation.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 설명 : FGC-FUN-050 1차 고정 허용오차 정책
 *
 * 0원·정확 날짜·정확 회차만 일치한다.
 *
 * @author yslee
 * @since 2026-08-14
 * @version 1.2
 */
@Component
public class ZeroTolerancePolicy implements TolerancePolicy {

    @Override
    public boolean matchesAmount(BigDecimal expectedAmount, BigDecimal actualAmount) {
        return Objects.requireNonNull(expectedAmount, "expectedAmount는 필수입니다.")
                .compareTo(Objects.requireNonNull(actualAmount, "actualAmount는 필수입니다.")) == 0;
    }

    @Override
    public boolean matchesDate(LocalDate expectedDate, LocalDate actualDate) {
        return Objects.requireNonNull(expectedDate, "expectedDate는 필수입니다.")
                .equals(Objects.requireNonNull(actualDate, "actualDate는 필수입니다."));
    }

    @Override
    public boolean matchesInstallment(Integer expectedInstallment, Integer actualInstallment) {
        return Objects.requireNonNull(expectedInstallment, "expectedInstallment는 필수입니다.")
                .equals(Objects.requireNonNull(actualInstallment, "actualInstallment는 필수입니다."));
    }
}
