package com.susukkang.fgc.arbitrage.dto;

import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 설명 : 차익거래 검증 결과 조회 조건을 전달한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageCheckSearchCondition {
    @DateTimeFormat(pattern = "yyyy-MM")
    private YearMonth month; // 정산월(YYYY-MM)
    private ArbitrageCheckStatus status; // 판정 결과
    private PaymentStage stage; // 지급 단계
    private Long insurerId; // 보험사 ID

    public LocalDate getMonthStart() {
        return month == null ? null : month.atDay(1);
    }

    public LocalDate getNextMonthStart() {
        return month == null ? null : month.plusMonths(1).atDay(1);
    }
}
